#!/usr/bin/env bash
#
# S7-08 — the back office on S3 + CloudFront, same-origin with the API (M-8, M-14, M-16).
#
# ONE distribution, two behaviours: default -> the private S3 bucket (via OAC), and /api/*
# -> the internal ALB (via a CloudFront VPC origin). That is what makes the back office
# same-origin with the API in production exactly as the Vite dev proxy does in development:
# no CORS is configured anywhere, and the operator's JSESSIONID/XSRF-TOKEN need no
# cross-site relaxation. Confirming this shape is what closes AD-002d's back-office origin
# half, which PROJECT_PLAN.md recorded as open with a recommendation attached.
#
# EVERY NON-OBVIOUS CHOICE BELOW COMES FROM A VERIFIED FINDING, not from memory. The
# research report is docs/sessions/ (S7-08 research) and docs/components/cloudfront-vpc-origin.md.
# The three that matter:
#
#  1. THERE IS NO CustomErrorResponses BLOCK, AND THERE MUST NOT BE ONE. `CustomErrorResponses`
#     is a DistributionConfig field; `CacheBehavior` has no equivalent, and CloudFront resolves
#     the mapping from the DISTRIBUTION regardless of which behaviour served the failing
#     request. The conventional SPA fallback (403/404 -> /index.html, 200) would therefore
#     rewrite GENUINE API 403s and 404s from the ALB into an HTML page with status 200 and
#     silently break the back office's session-expiry handling. The history fallback is done
#     instead with a CloudFront Function on the DEFAULT behaviour only: FunctionAssociations
#     IS per-behaviour, and a URI rewrite "doesn't change the cache behavior for the request
#     or the origin that an origin request is sent to".
#
#  2. THE ALB SECURITY GROUP IS NARROWED TO CloudFront's SERVICE-MANAGED SECURITY GROUP,
#     not to the origin-facing managed prefix list. Both are documented as valid. The prefix
#     list admits ANY CloudFront distribution in ANY AWS account; the service-managed group
#     restricts to OUR distributions. For an API that serves identity documents, that
#     difference is the whole point. The group only exists after the VPC origin deploys,
#     which is why the narrowing is the last step here rather than part of 08.
#
#  3. TLS: viewer -> CloudFront is HTTPS on the default *.cloudfront.net certificate;
#     CloudFront -> ALB is HTTP on AWS's network via the VPC origin, never the public
#     internet. `http-only` with HTTPPort 80 is a documented, valid VPC origin
#     configuration -- no HTTPS listener and therefore no certificate and no domain needed.
#     Record M-14 as satisfied on the public surface and deliberately not end-to-end.
#
# BL-066 WARNING, AND IT IS NOT A THEORY. ELB stores "the protocol used between the client
# and the load balancer" in X-Forwarded-Proto. CloudFront is the ALB's client and connects
# over HTTP, so the ALB emits X-Forwarded-Proto: http and Spring concludes the request was
# plaintext -- issuing JSESSIONID and XSRF-TOKEN WITHOUT `Secure`, which is the exact defect
# BL-066 exists to prevent. This script does not fix that; it makes it TESTABLE. Assert the
# cookies THROUGH CLOUDFRONT after a real sign-in: through the ALB directly the test would
# pass for the wrong reason.
#
# Idempotent. Re-running creates nothing twice and reports what already exists.
#
# Usage:  AWS_PROFILE=fru-deploy bash infra/aws/09-backoffice-cloudfront.sh
set -uo pipefail

AWS="${AWS_CLI:-/c/Program Files/Amazon/AWSCLIV2/aws.exe}"
export AWS_PAGER=""
export MSYS_NO_PATHCONV=1
REGION="${AWS_REGION:-eu-central-1}"
PREFIX="fru-staging"
BUCKET="${PREFIX}-backoffice"
HERE="$(cd "$(dirname "$0")" && pwd)"
DIST_SRC="$HERE/../../backoffice/dist"
# The WINDOWS aws.exe cannot open an MSYS path like /c/Users/... . bash CAN, so the existence
# guard below passes and the upload then fails -- which is exactly what happened at S7-08 and
# left the bucket empty behind a working distribution. Keep the MSYS form for the shell's own
# tests and hand the CLI a path it can actually open.
if command -v cygpath >/dev/null 2>&1; then
  DIST_SRC_CLI="$(cygpath -w "$DIST_SRC" 2>/dev/null)"
else
  DIST_SRC_CLI="$DIST_SRC"
fi

aws_() { "$AWS" --region "$REGION" "$@"; }
# CloudFront is a global service; its control plane lives in us-east-1.
awscf_() { "$AWS" --region us-east-1 "$@"; }
cli_path() { if command -v cygpath >/dev/null 2>&1; then echo "file://$(cygpath -w "$1" | tr '\\' '/')"; else echo "file://$1"; fi; }
# --function-code is a BLOB parameter: the CLI requires fileb://. With file:// the CLI sends
# the JavaScript source as a literal string and fails with an unhelpful error.
cli_pathb() { if command -v cygpath >/dev/null 2>&1; then echo "fileb://$(cygpath -w "$1" | tr '\\' '/')"; else echo "fileb://$1"; fi; }

RC=0
note() { echo "  $*"; }
fail() { echo "  FAIL  $*" >&2; RC=1; }

[ -f "$DIST_SRC/index.html" ] || { echo "REFUSING: $DIST_SRC/index.html not found. Run 'npm run build' in backoffice/ first." >&2; exit 1; }

ACCOUNT=$(aws_ sts get-caller-identity --query Account --output text)

# ---------------------------------------------------------------- S3, private
echo "== S3 bucket (private; CloudFront reaches it by OAC, nothing else reaches it) =="
if aws_ s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
  note "exists: $BUCKET"
else
  aws_ s3api create-bucket --bucket "$BUCKET" \
    --create-bucket-configuration "LocationConstraint=$REGION" >/dev/null \
    && note "created: $BUCKET" || fail "could not create the bucket"
  aws_ s3api put-bucket-tagging --bucket "$BUCKET" \
    --tagging 'TagSet=[{Key=Project,Value=fru},{Key=Environment,Value=staging}]' >/dev/null 2>&1
fi
# Belt and braces: the bucket must never be public. OAC is what grants CloudFront access.
aws_ s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true \
  >/dev/null && note "public access blocked (all four)" || fail "could not block public access"
aws_ s3api put-bucket-encryption --bucket "$BUCKET" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}' \
  >/dev/null 2>&1 && note "default encryption on"

echo
echo "== uploading the built back office =="
# index.html must never be cached hard: a stale index pins users to a deleted asset bundle.
# The fingerprinted assets under /assets/ are immutable and can be cached for a year.
aws_ s3 sync "$DIST_SRC_CLI" "s3://$BUCKET" --delete --exclude "index.html" \
  --cache-control "public,max-age=31536000,immutable" >/dev/null \
  && note "assets uploaded (immutable, 1 year)" || fail "asset upload failed"
aws_ s3 cp "$DIST_SRC_CLI/index.html" "s3://$BUCKET/index.html" \
  --cache-control "no-cache" --content-type "text/html" >/dev/null \
  && note "index.html uploaded (no-cache)" || fail "index.html upload failed"

# ---------------------------------------------------------------- OAC
echo
echo "== origin access control =="
OAC_ID=$(awscf_ cloudfront list-origin-access-controls \
  --query "OriginAccessControlList.Items[?Name=='${PREFIX}-backoffice-oac'].Id | [0]" --output text 2>/dev/null)
if [ -z "$OAC_ID" ] || [ "$OAC_ID" = "None" ]; then
  OAC_ID=$(awscf_ cloudfront create-origin-access-control --origin-access-control-config \
    "Name=${PREFIX}-backoffice-oac,Description=Back office bucket,SigningProtocol=sigv4,SigningBehavior=always,OriginAccessControlOriginType=s3" \
    --query 'OriginAccessControl.Id' --output text) \
    && note "created OAC" || fail "could not create the OAC"
else
  note "OAC exists"
fi

# ---------------------------------------------------------------- SPA fallback function
echo
echo "== CloudFront Function: SPA history fallback (default behaviour ONLY) =="
FN_NAME="${PREFIX}-spa-fallback"
FN_FILE="$HERE/.spa-fallback.js"
cat > "$FN_FILE" <<'JS'
function handler(event) {
    var request = event.request;
    var uri = request.uri;
    // Anything that looks like a file (has a dot) or lives under /assets/ is a real object.
    // Everything else is a client-side route and must be served index.html so a deep link
    // or a refresh does not 404. This function is attached to the DEFAULT behaviour only,
    // so /api/* never reaches it and genuine API 404s are never rewritten.
    if (uri !== '/' && uri.indexOf('.') === -1) {
        request.uri = '/index.html';
    }
    return request;
}
JS
FN_ETAG=$(awscf_ cloudfront describe-function --name "$FN_NAME" --query 'ETag' --output text 2>/dev/null)
if [ -z "$FN_ETAG" ] || [ "$FN_ETAG" = "None" ]; then
  awscf_ cloudfront create-function --name "$FN_NAME" \
    --function-config "Comment=SPA history fallback,Runtime=cloudfront-js-2.0" \
    --function-code "$(cli_pathb "$FN_FILE")" >/dev/null \
    && note "created function" || fail "could not create the function"
  FN_ETAG=$(awscf_ cloudfront describe-function --name "$FN_NAME" --query 'ETag' --output text 2>/dev/null)
  awscf_ cloudfront publish-function --name "$FN_NAME" --if-match "$FN_ETAG" >/dev/null \
    && note "published function" || fail "could not publish the function"
else
  note "function exists"
fi
rm -f "$FN_FILE"
FN_ARN=$(awscf_ cloudfront describe-function --name "$FN_NAME" \
  --query 'FunctionSummary.FunctionMetadata.FunctionARN' --output text 2>/dev/null)

# ---------------------------------------------------------------- VPC origin
echo
echo "== CloudFront VPC origin -> the internal ALB =="
ALB_ARN=$(aws_ elbv2 describe-load-balancers --names "${PREFIX}-alb" \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text 2>/dev/null)
ALB_DNS=$(aws_ elbv2 describe-load-balancers --names "${PREFIX}-alb" \
  --query 'LoadBalancers[0].DNSName' --output text 2>/dev/null)
[ -n "$ALB_ARN" ] && [ "$ALB_ARN" != "None" ] || { echo "REFUSING: no ${PREFIX}-alb. Run 08 first." >&2; exit 1; }

VPCO_ID=$(awscf_ cloudfront list-vpc-origins \
  --query "VpcOriginList.Items[?Name=='${PREFIX}-alb'].Id | [0]" --output text 2>/dev/null)
if [ -z "$VPCO_ID" ] || [ "$VPCO_ID" = "None" ]; then
  VPCO_FILE="$HERE/.vpc-origin.json"
  cat > "$VPCO_FILE" <<JSON
{
  "Name": "${PREFIX}-alb",
  "Arn": "${ALB_ARN}",
  "HTTPPort": 80,
  "HTTPSPort": 443,
  "OriginProtocolPolicy": "http-only"
}
JSON
  VPCO_ID=$(awscf_ cloudfront create-vpc-origin --vpc-origin-endpoint-config "$(cli_path "$VPCO_FILE")" \
    --query 'VpcOrigin.Id' --output text) \
    && note "created VPC origin" || fail "could not create the VPC origin"
  rm -f "$VPCO_FILE"
else
  note "VPC origin exists"
fi

if [ -n "$VPCO_ID" ] && [ "$VPCO_ID" != "None" ]; then
  echo "  waiting for Deployed (documented as up to 15 minutes)..."
  for _ in $(seq 1 60); do
    ST=$(awscf_ cloudfront get-vpc-origin --id "$VPCO_ID" --query 'VpcOrigin.Status' --output text 2>/dev/null)
    echo "    status=$ST"
    [ "$ST" = "Deployed" ] && break
    sleep 30
  done
  [ "$ST" = "Deployed" ] || fail "VPC origin did not reach Deployed (last status: $ST)"
fi

# ---------------------------------------------------------------- distribution
echo
echo "== distribution: default -> S3, /api/* -> the VPC origin =="
DIST_ID=$(awscf_ cloudfront list-distributions \
  --query "DistributionList.Items[?Comment=='${PREFIX} back office and API'].Id | [0]" --output text 2>/dev/null)
if [ -z "$DIST_ID" ] || [ "$DIST_ID" = "None" ]; then
  # FIXED CallerReference, not a timestamp. CloudFront refuses a create-distribution whose
  # CallerReference it has seen before, which is a second line of defence behind the list-
  # query above: if that query transiently fails, a timestamped reference would have created
  # a SECOND distribution serving the same content. Found by review.
  DIST_FILE="$HERE/.distribution.json"
  cat > "$DIST_FILE" <<JSON
{
  "CallerReference": "${PREFIX}-backoffice-api-v1",
  "Comment": "${PREFIX} back office and API",
  "Enabled": true,
  "DefaultRootObject": "index.html",
  "Origins": {
    "Quantity": 2,
    "Items": [
      {
        "Id": "s3-backoffice",
        "DomainName": "${BUCKET}.s3.${REGION}.amazonaws.com",
        "OriginAccessControlId": "${OAC_ID}",
        "S3OriginConfig": { "OriginAccessIdentity": "" }
      },
      {
        "Id": "vpc-alb-api",
        "DomainName": "${ALB_DNS}",
        "VpcOriginConfig": {
          "VpcOriginId": "${VPCO_ID}",
          "OriginReadTimeout": 60,
          "OriginKeepaliveTimeout": 5
        }
      }
    ]
  },
  "DefaultCacheBehavior": {
    "TargetOriginId": "s3-backoffice",
    "ViewerProtocolPolicy": "redirect-to-https",
    "CachePolicyId": "658327ea-f89d-4fab-a63d-7e88639e58f6",
    "AllowedMethods": { "Quantity": 2, "Items": ["GET","HEAD"],
                        "CachedMethods": { "Quantity": 2, "Items": ["GET","HEAD"] } },
    "Compress": true,
    "FunctionAssociations": {
      "Quantity": 1,
      "Items": [ { "FunctionARN": "${FN_ARN}", "EventType": "viewer-request" } ]
    }
  },
  "CacheBehaviors": {
    "Quantity": 1,
    "Items": [
      {
        "PathPattern": "/api/*",
        "TargetOriginId": "vpc-alb-api",
        "ViewerProtocolPolicy": "https-only",
        "CachePolicyId": "4135ea2d-6df8-44a3-9df3-4b5a84be39ad",
        "OriginRequestPolicyId": "216adef6-5c7f-47e4-b989-5492eafa07d3",
        "AllowedMethods": {
          "Quantity": 7,
          "Items": ["GET","HEAD","OPTIONS","PUT","PATCH","POST","DELETE"],
          "CachedMethods": { "Quantity": 2, "Items": ["GET","HEAD"] }
        },
        "Compress": true
      }
    ]
  },
  "ViewerCertificate": { "CloudFrontDefaultCertificate": true }
}
JSON
  DIST_ID=$(awscf_ cloudfront create-distribution --distribution-config "$(cli_path "$DIST_FILE")" \
    --query 'Distribution.Id' --output text) \
    && note "created distribution" || fail "could not create the distribution"
  rm -f "$DIST_FILE"
else
  note "distribution exists"
fi

DIST_DOMAIN=""
if [ -n "$DIST_ID" ] && [ "$DIST_ID" != "None" ]; then
  DIST_DOMAIN=$(awscf_ cloudfront get-distribution --id "$DIST_ID" --query 'Distribution.DomainName' --output text 2>/dev/null)
  note "domain: https://${DIST_DOMAIN}"

  # --- bucket policy, only now: it must name the distribution ARN, which did not exist before.
  aws_ s3api put-bucket-policy --bucket "$BUCKET" --policy "{
    \"Version\": \"2012-10-17\",
    \"Statement\": [{
      \"Sid\": \"AllowCloudFrontServicePrincipalReadOnly\",
      \"Effect\": \"Allow\",
      \"Principal\": { \"Service\": \"cloudfront.amazonaws.com\" },
      \"Action\": \"s3:GetObject\",
      \"Resource\": \"arn:aws:s3:::${BUCKET}/*\",
      \"Condition\": { \"StringEquals\": { \"AWS:SourceArn\": \"arn:aws:cloudfront::${ACCOUNT}:distribution/${DIST_ID}\" } }
    }]
  }" >/dev/null && note "bucket policy scoped to this distribution only" || fail "could not set the bucket policy"
fi

# ---------------------------------------------------------------- narrow the ALB SG
echo
echo "== narrowing the ALB security group to CloudFront's service-managed group =="
. "$HERE/network-ids.env"
SGCF=$(aws_ ec2 describe-security-groups \
  --filters "Name=vpc-id,Values=${VPC_ID}" "Name=group-name,Values=CloudFront-VPCOrigins-Service-SG*" \
  --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null)
if [ -n "$SGCF" ] && [ "$SGCF" != "None" ]; then
  note "found CloudFront service-managed SG"
  aws_ ec2 authorize-security-group-ingress --group-id "$SG_ALB" \
    --protocol tcp --port 80 --source-group "$SGCF" >/dev/null 2>&1 \
    && note "allowed :80 from the CloudFront service SG" || note "that rule already exists"
  # Only now is the placeholder safe to remove -- and it MUST be removed: port 80 from the
  # whole VPC CIDR means any workload in the VPC could reach the API bypassing CloudFront.
  aws_ ec2 revoke-security-group-ingress --group-id "$SG_ALB" \
    --ip-permissions "IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=10.0.0.0/16}]" >/dev/null 2>&1 \
    && note "revoked the VPC-CIDR placeholder" || note "placeholder already absent"
else
  fail "could not find CloudFront-VPCOrigins-Service-SG -- the VPC-CIDR placeholder is STILL OPEN. Re-run once the VPC origin has deployed."
fi

# ---------------------------------------------------------------- record
OUT="$HERE/backoffice-ids.env"
cat > "$OUT" <<EOF
# Generated by infra/aws/09-backoffice-cloudfront.sh — identifiers, not secrets. Gitignored.
BUCKET=$BUCKET
OAC_ID=$OAC_ID
FN_ARN=$FN_ARN
VPC_ORIGIN_ID=$VPCO_ID
DISTRIBUTION_ID=$DIST_ID
DISTRIBUTION_DOMAIN=$DIST_DOMAIN
EOF
echo
note "wrote $OUT"

echo
if [ "$RC" -ne 0 ]; then
  echo "== 09 complete WITH FAILURES (exit 1) =="
else
  echo "== 09 complete. The distribution takes several minutes to propagate. =="
  echo "   Back office: https://${DIST_DOMAIN}"
  echo "   BL-066 next: sign in THROUGH THIS URL and assert Secure on both cookies."
fi
exit "$RC"
