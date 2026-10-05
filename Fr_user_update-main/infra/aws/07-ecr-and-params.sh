#!/usr/bin/env bash
#
# S7-07 (part 1 of 3) — the ECR repository and the Parameter Store configuration the backend
# task reads. Creates no compute; 08 does the ALB and the ECS service, 09 the back office.
#
# WHY A SCRIPT AND NOT A FEW CLI CALLS. Session 1's review found that two of three claimed
# secrets had been created by an ad-hoc command line and by nothing in this directory, while
# infra/aws/ advertised itself as the record of what was provisioned. Everything the app tier
# needs is therefore written down here, first, and run from here.
#
# IDEMPOTENT. Every step checks before it writes and says which it did. Safe to re-run; it
# does not rotate, delete or overwrite anything that already carries a value.
#
# WHAT IS NOT HERE: UQUDO_CLIENT_ID and UQUDO_CLIENT_SECRET. They are the tenant's own
# credentials, entered by the product owner directly into Secrets Manager (fru/staging/uqudo,
# keys client_id and client_secret) and never relayed through a session, a script or a log.
# This script verifies that secret EXISTS and is structurally sound. It DOES read the
# SecretString -- it must, to check the values are not empty, whitespace-padded or
# placeholder prose -- but it prints only a verdict, never a value. The distinction
# matters: an earlier version of this comment claimed it never read them at all.
#
# Usage:  AWS_PROFILE=fru-deploy bash infra/aws/07-ecr-and-params.sh
set -uo pipefail

AWS="${AWS_CLI:-/c/Program Files/Amazon/AWSCLIV2/aws.exe}"
export AWS_PAGER=""
export MSYS_NO_PATHCONV=1
REGION="${AWS_REGION:-eu-central-1}"
PREFIX="fru-staging"
REPO="${PREFIX}-backend"
PARAM_ROOT="/fru/staging"

aws_() { "$AWS" --region "$REGION" "$@"; }

RC=0
note() { echo "  $*"; }
fail() { echo "  FAIL  $*" >&2; RC=1; }

echo "== ECR repository =="
if aws_ ecr describe-repositories --repository-names "$REPO" >/dev/null 2>&1; then
  note "exists (left alone): $REPO"
else
  # scanOnPush: a bank should not learn about a base-image CVE from a pen test.
  # IMMUTABLE tags: a mutable tag means "the image that was reviewed" and "the image that is
  # running" can differ while both answer to the same name. Deployments reference digests or
  # new tags; nothing overwrites a tag that was already deployed.
  aws_ ecr create-repository \
    --repository-name "$REPO" \
    --image-tag-mutability IMMUTABLE \
    --image-scanning-configuration scanOnPush=true \
    --encryption-configuration encryptionType=AES256 \
    --tags Key=Project,Value=fru Key=Environment,Value=staging \
    --query 'repository.repositoryName' --output text >/dev/null \
    && note "created: $REPO" \
    || fail "could not create the ECR repository"
fi

# A lifecycle policy is not tidiness: untagged layers accrue storage cost forever, and an ECR
# repository is one of the few things in this stack that grows without anyone deciding to.
if aws_ ecr get-lifecycle-policy --repository-name "$REPO" >/dev/null 2>&1; then
  note "lifecycle policy already set"
else
  aws_ ecr put-lifecycle-policy --repository-name "$REPO" --lifecycle-policy-text \
    '{"rules":[{"rulePriority":1,"description":"expire untagged images after 14 days","selection":{"tagStatus":"untagged","countType":"sinceImagePushed","countUnit":"days","countNumber":14},"action":{"type":"expire"}}]}' \
    >/dev/null && note "lifecycle policy set" || fail "could not set the lifecycle policy"
fi

echo
echo "== Parameter Store: configuration, never baked into the image (R-007) =="
# put_param writes only if absent. An existing value is left alone and reported, so re-running
# this script can never quietly change what a running task reads.
put_param() { # $1 = name, $2 = value, $3 = description
  if aws_ ssm get-parameter --name "$1" >/dev/null 2>&1; then
    note "exists (left alone): $1"
    return 0
  fi
  aws_ ssm put-parameter --name "$1" --value "$2" --type String --description "$3" \
    --tags Key=Project,Value=fru Key=Environment,Value=staging >/dev/null \
    && note "created: $1" \
    || fail "could not create $1"
}

# Uqudo endpoints. Values transcribed from application.properties:109-112, which records them
# as observed at S1-02. They are configuration because the TENANT CHANGES BEFORE PRODUCTION
# (CLAUDE.md), so production overrides these paths rather than rebuilding the image.
put_param "$PARAM_ROOT/uqudo/auth-url" "https://auth.uqudo.io/api" "Uqudo token issuance base"
put_param "$PARAM_ROOT/uqudo/api-base" "https://id.uqudo.io" "Uqudo API base"
put_param "$PARAM_ROOT/uqudo/jwks-url" "https://id.uqudo.io/api/.well-known/jwks.json" "Uqudo JWKS, for server-side JWS verification"
put_param "$PARAM_ROOT/uqudo/issuer" "https://id.uqudo.io" "Expected iss claim on a Uqudo JWS"

# Civil Registry. Real for the acceptance walk. Endpoint per docs/components/civil-registry.md:48
# and the S3-14 adapter session -- a public hostname on 5353, which 01-network.sh's egress rules
# already allow. Whether the bank permits THIS stack's NAT address is a separate question and is
# checked before the walk, not assumed here.
put_param "$PARAM_ROOT/civil-registry/endpoint" "https://mb1.sfbank-sd.com:5353/CRSAPI/Services/GetCRSData" "Civil Registry GetCRSData (AD-002b)"
# Added S7-12: application-aws.properties bound the core-banking SELECTOR but never the
# endpoint the `http` selector demands, so switching to the real middleware would have failed at
# startup after a deploy. Reachability from the backend's own network path proven the same day
# (11-egress-probe.sh: mb1...:9494 tcp=OPEN).
put_param "$PARAM_ROOT/core-banking/endpoint" "https://mb1.sfbank-sd.com:9494/OMNI_PH3/resources/bankRoutes/CheckAccount" "CheckAccount middleware (M-2, AD-007)"

# Airtel Sudan SMS gateway (BL-080). Added S8-02, when the SMS selector was first flipped to `http`
# on this stack. CONFIGURATION, not secrets: the gateway host changes before production and the
# sender id is customer-visible (it is printed on every message the bank sends). The account
# USERNAME and PASSWORD are the other half and are NOT here -- see the preconditions block below.
# Reachability of this host from the backend's own SG/subnet/NAT was proven before wiring
# (11-egress-probe.sh, PROBE_EXTRA=airtel-sms=...: tcp=OPEN).
put_param "$PARAM_ROOT/sms/endpoint" "https://www.airtel.sd/api/html_send_sms/" "Airtel Sudan SMS send endpoint (BL-080)"
put_param "$PARAM_ROOT/sms/sender-id" "SFB" "Customer-visible SMS sender id. UNREGISTERED -- see R-041"

echo
echo "== preconditions this script verifies but does not create =="
# The Uqudo secret is the product owner's to file. Verify it is present and shaped correctly,
# and never read a value: a structural check is the most this script may know.
if aws_ secretsmanager describe-secret --secret-id fru/staging/uqudo >/dev/null 2>&1; then
  # STRUCTURE ONLY, and the values never leave this pipeline: the check reads the secret,
  # derives four booleans, and prints only those. CLAUDE.md forbids these credentials in a
  # log or a report even redacted, so nothing here may echo one.
  #
  # WHY IT CHECKS MORE THAN THE KEY NAMES. It used to check only that both keys existed. The
  # first attempt at filing this secret stored the PLACEHOLDER PROSE from the instructions
  # ("the project tenant's Uqudo client ID") and a key-name check passed it as fine. The
  # tightened version below is what actually caught that, and it belongs in this script rather
  # than in a session's scrollback -- an ad hoc check that lives nowhere is the exact gap the
  # last review raised about the two secrets 06 now files. Found by review.
  VERDICT=$(aws_ secretsmanager get-secret-value --secret-id fru/staging/uqudo               --query SecretString --output text 2>/dev/null             | python -c "
import sys, json, re
try:
    d = json.loads(sys.stdin.read())
except Exception:
    print('NOT_JSON'); raise SystemExit
if not isinstance(d, dict):
    print('NOT_OBJECT'); raise SystemExit
bad = []
for k in ('client_id', 'client_secret'):
    v = d.get(k)
    if v is None:                       bad.append(k + ':MISSING'); continue
    if not isinstance(v, str) or not v: bad.append(k + ':EMPTY'); continue
    if v != v.strip():                  bad.append(k + ':WHITESPACE')
    if re.search(r'\s', v):              bad.append(k + ':CONTAINS_WHITESPACE')
    if len(v.strip()) < 8:              bad.append(k + ':TOO_SHORT')
    # Prose, not a credential: real ones are opaque tokens with no English in them.
    if len(set(re.findall(r\"[a-z']+\", v.lower())) & {'the','project','tenant','client','your','here','value','secret','uqudo'}) >= 2:
        bad.append(k + ':LOOKS_LIKE_PROSE')
print('OK' if not bad else '|'.join(bad))
" 2>/dev/null)
  if [ "$VERDICT" = "OK" ]; then
    note "fru/staging/uqudo present, both keys structurally sound (values never printed)"
  else
    fail "fru/staging/uqudo is not usable: ${VERDICT:-could not be parsed}"
  fi
else
  fail "fru/staging/uqudo does not exist - the product owner must file the tenant credentials first"
fi

# The Airtel account credentials are the product owner's to file, for the same reason the Uqudo
# pair is: they are the bank's own credentials and must never be relayed through a session, a
# script or a log. Presence and structure only -- this never reads a value, because unlike the
# Uqudo check there is no shape to validate that would not also risk printing one.
if aws_ secretsmanager describe-secret --secret-id fru/staging/sms >/dev/null 2>&1; then
  SMS_KEYS=$(aws_ secretsmanager get-secret-value --secret-id fru/staging/sms     --query SecretString --output text 2>/dev/null | python -c "
import sys, json
try:
    d = json.loads(sys.stdin.read())
except Exception:
    print('NOT_JSON'); raise SystemExit
if not isinstance(d, dict):
    print('NOT_OBJECT'); raise SystemExit
bad = [k + (':MISSING' if d.get(k) is None else ':EMPTY')
       for k in ('username', 'password')
       if not isinstance(d.get(k), str) or not d.get(k, '').strip()]
print('OK' if not bad else '|'.join(bad))
" 2>/dev/null)
  if [ "$SMS_KEYS" = "OK" ]; then
    note "fru/staging/sms present, both keys non-empty (values never printed)"
  else
    fail "fru/staging/sms is not usable: ${SMS_KEYS:-could not be parsed}"
  fi
else
  fail "fru/staging/sms does not exist - the product owner must file the Airtel account credentials (keys: username, password) before the SMS selector may be 'http'"
fi

for s in fru/staging/db/app fru/staging/db/migrator; do
  aws_ secretsmanager describe-secret --secret-id "$s" >/dev/null 2>&1 \
    && note "$s present" \
    || fail "$s missing - run infra/aws/06-secrets.sh and the migration first"
done

echo
if [ "$RC" -ne 0 ]; then
  echo "== 07 complete WITH FAILURES (exit 1) =="
else
  echo "== 07 complete =="
fi
exit "$RC"
