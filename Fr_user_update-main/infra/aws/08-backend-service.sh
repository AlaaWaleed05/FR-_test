#!/usr/bin/env bash
#
# S7-07 (part 2 of 3) — the internal ALB, the ECS cluster and the Fargate service (M-1).
# Run 07-ecr-and-params.sh first, and push an image, or the service has nothing to start.
#
# THE NETWORK POSTURE, AND WHY IT IS NOT THE ONE SESSION 1 ANTICIPATED. 01-network.sh created
# an ALB security group open on 443/80 to the internet, because a public ALB was the assumed
# shape. It is not the shape built here. ACM will not issue a certificate for an
# *.elb.amazonaws.com name, so a public ALB could only have served plaintext until a domain
# exists -- and the acceptance walk carries a real person's national record.
#
# Instead: the ALB is INTERNAL, and CloudFront reaches it through a VPC origin (09). TLS
# terminates at CloudFront, whose default *.cloudfront.net certificate is free and valid, and
# the CloudFront-to-ALB hop stays on AWS's network instead of crossing the internet. The
# backend is not reachable from the internet at all, by any address, which is a stronger
# position than the public ALB this SG was drawn for -- and it is the production shape, not a
# staging stand-in. A bank-owned domain later adds an alternate domain name and an ACM
# certificate to the SAME distribution; it does not change any of this.
#
# M-14 IS THEREFORE SATISFIED ON THE PUBLIC SURFACE AND DELIBERATELY NOT END-TO-END. Browser
# to CloudFront is TLS. CloudFront to ALB is HTTP inside AWS. Record it that way rather than
# claiming more: a reviewer who wants TLS on that hop too needs a certificate, which needs a
# domain, which is BL-072's class of bank dependency.
#
# Usage:  AWS_PROFILE=fru-deploy bash infra/aws/08-backend-service.sh
set -uo pipefail

AWS="${AWS_CLI:-/c/Program Files/Amazon/AWSCLIV2/aws.exe}"
export AWS_PAGER=""
export MSYS_NO_PATHCONV=1
REGION="${AWS_REGION:-eu-central-1}"
PREFIX="fru-staging"
HERE="$(cd "$(dirname "$0")" && pwd)"

IMAGE_TAG="${IMAGE_TAG:-}"
if [ -z "$IMAGE_TAG" ]; then
  echo "REFUSING: set IMAGE_TAG to the tag pushed to ECR, e.g. IMAGE_TAG=0.0.1-20260906t2048" >&2
  echo "A 'latest' default would make the running image unidentifiable, which is the thing" >&2
  echo "an IMMUTABLE-tag repository exists to prevent." >&2
  exit 1
fi

. "$HERE/network-ids.env"
for v in VPC_ID SUBNET_APP_A SUBNET_APP_B SG_ALB SG_ECS; do
  eval ": \"\${$v:?network-ids.env is missing $v - run infra/aws/00-discover-ids.sh}\""
done

aws_() { "$AWS" --region "$REGION" "$@"; }
RC=0
note() { echo "  $*"; }
fail() { echo "  FAIL  $*" >&2; RC=1; }

ACCOUNT=$(aws_ sts get-caller-identity --query Account --output text)
REGISTRY="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"
IMAGE="${REGISTRY}/${PREFIX}-backend:${IMAGE_TAG}"

echo "== the image this service will run =="
if aws_ ecr describe-images --repository-name "${PREFIX}-backend" --image-ids imageTag="$IMAGE_TAG" >/dev/null 2>&1; then
  note "found in ECR: ${PREFIX}-backend:${IMAGE_TAG}"
else
  echo "REFUSING: ${PREFIX}-backend:${IMAGE_TAG} is not in ECR. Push it first." >&2
  exit 1
fi

# ---------------------------------------------------------------- database endpoint
# Discovered, never written into the repository: the endpoint is account-specific state.
echo
echo "== database endpoint =="
DB_HOST=$(aws_ rds describe-db-instances --db-instance-identifier "${PREFIX}-db" \
  --query 'DBInstances[0].Endpoint.Address' --output text 2>/dev/null)
if [ -z "$DB_HOST" ] || [ "$DB_HOST" = "None" ]; then
  echo "REFUSING: could not resolve the RDS endpoint for ${PREFIX}-db" >&2
  exit 1
fi
note "resolved (value not printed)"

# ---------------------------------------------------------------- ALB security group
# Replace the internet-facing ingress 01-network.sh drew for a PUBLIC ALB. Leaving 443/80 open
# to 0.0.0.0/0 on an internal load balancer would not expose anything -- an internal ALB has no
# public address -- but a rule that says "the internet may reach this" when the design says the
# opposite is exactly the kind of drift a later reader trusts and a reviewer flags.
echo
echo "== ALB security group: internal posture =="
aws_ ec2 revoke-security-group-ingress --group-id "$SG_ALB" \
  --ip-permissions "IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0}]" >/dev/null 2>&1 \
  && note "revoked 443 from 0.0.0.0/0" || note "443 from 0.0.0.0/0 already absent"
aws_ ec2 revoke-security-group-ingress --group-id "$SG_ALB" \
  --ip-permissions "IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=0.0.0.0/0}]" >/dev/null 2>&1 \
  && note "revoked 80 from 0.0.0.0/0" || note "80 from 0.0.0.0/0 already absent"
# In-VPC only, and ONLY until 09 narrows it to the CloudFront VPC origin.
#
# CONDITIONAL, AND THAT IS THE POINT. 09 revokes this exact rule once CloudFront's
# service-managed security group exists, because port 80 from the whole VPC CIDR lets any
# workload in the VPC reach the API bypassing CloudFront. Re-running 08 is the documented way
# to deploy a new image, so an unconditional authorize here silently RE-OPENED that hole on
# every redeploy, while 09 -- not re-run for an image change -- was not there to close it
# again. Found by review, and confirmed live: the security group held BOTH rules after the
# BL-066 redeploy. If a source-group rule on :80 already exists, the narrowing has happened
# and this placeholder must stay absent.
if aws_ ec2 describe-security-groups --group-ids "$SG_ALB"      --query 'SecurityGroups[0].IpPermissions[?FromPort==`80`].UserIdGroupPairs[]'      --output text 2>/dev/null | grep -q "sg-"; then
  note "CloudFront source-group rule present; NOT re-adding the VPC-CIDR placeholder"
else
  aws_ ec2 authorize-security-group-ingress --group-id "$SG_ALB"     --ip-permissions "IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=10.0.0.0/16,Description=in-VPC-only-until-09-narrows-to-the-vpc-origin}]" >/dev/null 2>&1     && note "allowed 80 from the VPC CIDR (placeholder until 09 runs)" || note "VPC-CIDR rule already present"
fi

# ---------------------------------------------------------------- log group
echo
echo "== log group =="
if aws_ logs describe-log-groups --log-group-name-prefix "/ecs/${PREFIX}-backend" \
     --query 'logGroups[?logGroupName==`/ecs/'"${PREFIX}"'-backend`]' --output text | grep -q .; then
  note "exists: /ecs/${PREFIX}-backend"
else
  aws_ logs create-log-group --log-group-name "/ecs/${PREFIX}-backend" \
    --tags Project=fru,Environment=staging >/dev/null && note "created: /ecs/${PREFIX}-backend" \
    || fail "could not create the log group"
  # Logs of a customer journey are not something to keep forever by accident.
  aws_ logs put-retention-policy --log-group-name "/ecs/${PREFIX}-backend" --retention-in-days 30 \
    >/dev/null && note "retention 30 days" || fail "could not set log retention"
fi

# ---------------------------------------------------------------- execution role
echo
echo "== task execution role =="
EXEC_ROLE="${PREFIX}-ecs-execution-role"
if aws_ iam get-role --role-name "$EXEC_ROLE" >/dev/null 2>&1; then
  note "exists: $EXEC_ROLE"
else
  aws_ iam create-role --role-name "$EXEC_ROLE" \
    --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}' \
    --tags Key=Project,Value=fru Key=Environment,Value=staging >/dev/null \
    && note "created: $EXEC_ROLE" || fail "could not create $EXEC_ROLE"
  aws_ iam attach-role-policy --role-name "$EXEC_ROLE" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy >/dev/null \
    && note "attached AmazonECSTaskExecutionRolePolicy (ECR pull + logs)" \
    || fail "could not attach the managed execution policy"
fi
# The managed policy covers ECR and CloudWatch but NOT the secret and parameter reads the task
# definition performs on the task's behalf. Scoped to this stack's paths, never Resource:"*" --
# session 1's review found an out-of-band role policy that had exactly that and fixed it.
aws_ iam put-role-policy --role-name "$EXEC_ROLE" --policy-name "${PREFIX}-injected-config" \
  --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"secretsmanager:GetSecretValue\"],\"Resource\":\"arn:aws:secretsmanager:${REGION}:${ACCOUNT}:secret:fru/staging/*\"},{\"Effect\":\"Allow\",\"Action\":[\"ssm:GetParameters\"],\"Resource\":\"arn:aws:ssm:${REGION}:${ACCOUNT}:parameter/fru/staging/*\"}]}" \
  >/dev/null && note "inline policy set: secrets + parameters, scoped to fru/staging" \
  || fail "could not set the inline execution policy"

# ---------------------------------------------------------------- cluster
echo
echo "== ECS cluster =="
if aws_ ecs describe-clusters --clusters "$PREFIX" --query 'clusters[?status==`ACTIVE`]' --output text | grep -q .; then
  note "exists: $PREFIX"
else
  aws_ ecs create-cluster --cluster-name "$PREFIX" \
    --settings name=containerInsights,value=enabled \
    --tags key=Project,value=fru key=Environment,value=staging >/dev/null \
    && note "created: $PREFIX" || fail "could not create the cluster"
fi

# ---------------------------------------------------------------- target group + ALB
echo
echo "== internal ALB and target group =="
TG_ARN=$(aws_ elbv2 describe-target-groups --names "${PREFIX}-backend-tg" \
  --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null)
if [ -z "$TG_ARN" ] || [ "$TG_ARN" = "None" ]; then
  # target-type ip: Fargate awsvpc tasks are registered by ENI address, not by instance.
  # The health check is /actuator/health, which reports DOWN when the database is unreachable
  # -- so a task that cannot reach RDS stops receiving traffic rather than failing requests.
  TG_ARN=$(aws_ elbv2 create-target-group --name "${PREFIX}-backend-tg" \
    --protocol HTTP --port 8080 --vpc-id "$VPC_ID" --target-type ip \
    --health-check-protocol HTTP --health-check-path /actuator/health \
    --health-check-interval-seconds 30 --health-check-timeout-seconds 5 \
    --healthy-threshold-count 2 --unhealthy-threshold-count 3 --matcher HttpCode=200 \
    --query 'TargetGroups[0].TargetGroupArn' --output text) \
    && note "created target group" || fail "could not create the target group"
else
  note "target group exists"
fi

ALB_ARN=$(aws_ elbv2 describe-load-balancers --names "${PREFIX}-alb" \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text 2>/dev/null)
if [ -z "$ALB_ARN" ] || [ "$ALB_ARN" = "None" ]; then
  ALB_ARN=$(aws_ elbv2 create-load-balancer --name "${PREFIX}-alb" \
    --type application --scheme internal \
    --subnets "$SUBNET_APP_A" "$SUBNET_APP_B" --security-groups "$SG_ALB" \
    --tags Key=Project,Value=fru Key=Environment,Value=staging \
    --query 'LoadBalancers[0].LoadBalancerArn' --output text) \
    && note "created INTERNAL ALB" || fail "could not create the ALB"
else
  note "ALB exists"
fi

if [ -n "$ALB_ARN" ] && [ "$ALB_ARN" != "None" ]; then
  if aws_ elbv2 describe-listeners --load-balancer-arn "$ALB_ARN" \
       --query 'Listeners[?Port==`80`]' --output text | grep -q .; then
    note "listener :80 exists"
  else
    aws_ elbv2 create-listener --load-balancer-arn "$ALB_ARN" --protocol HTTP --port 80 \
      --default-actions Type=forward,TargetGroupArn="$TG_ARN" >/dev/null \
      && note "created listener :80 -> target group" || fail "could not create the listener"
  fi
fi

# ---------------------------------------------------------------- task definition
echo
echo "== task definition =="
# environment vs secrets, deliberately: the six selectors are LITERAL and visible here, because
# S7-11's whole point is that a human can read which side of the stub/real line this deployment
# sits on without resolving anything. Credentials and endpoints are references, resolved at task
# start, so rotating a secret or repointing an endpoint needs no new task definition revision.
#
# FRU_MESSAGING_SMS_PROVIDER WAS `stub` UNTIL S8-02 AND IS NOW `http`. Read that literal as what
# it is: every OTP and every status notification this stack sends now costs real money and lands
# on a real handset. WhatsApp and email stay `stub` -- they have no real adapter at all, and
# MessageSenderConfiguration refuses `http` for them at startup (SMS-only release). The four
# fru.messaging.sms.http.* values the selector demands are injected below; the username and
# password come from fru/staging/sms, which 07 verifies exists and which the product owner files
# directly. A missing one fails startup naming the property, never the value.
#
# FRU_MESSAGING_WHATSAPP_ENABLED IS HERE BECAUSE ITS ABSENCE WAS A DEFECT (BL-097, closed here).
# S8-08 set it to `false` by hand on the live task definition; this document did not carry it, and
# this script is the documented way to deploy a new image. So the next deployment through the
# documented path WOULD have dropped it silently, fru.messaging.whatsapp.enabled would have
# reverted to its default `true`, and the stack would have gone back to minting WhatsApp
# challenges that the `stub` provider above can never deliver -- a customer who ticks the box
# waiting for a code that does not exist. It never actually happened: the flag survived on every
# revision because each was hand-edited, which is exactly what made the gap invisible. The
# selector and the enablement flag are two different things and both have to be stated.
TASKDEF_JSON=$(cat <<JSON
{
  "family": "${PREFIX}-backend",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "runtimePlatform": { "cpuArchitecture": "X86_64", "operatingSystemFamily": "LINUX" },
  "executionRoleArn": "arn:aws:iam::${ACCOUNT}:role/${EXEC_ROLE}",
  "containerDefinitions": [
    {
      "name": "backend",
      "image": "${IMAGE}",
      "essential": true,
      "portMappings": [ { "containerPort": 8080, "protocol": "tcp" } ],
      "environment": [
        { "name": "DB_HOST", "value": "${DB_HOST}" },
        { "name": "DB_NAME", "value": "fru" },
        { "name": "FRU_CORE_BANKING_CLIENT", "value": "stub" },
        { "name": "FRU_CIVIL_REGISTRY_CLIENT", "value": "http" },
        { "name": "FRU_UQUDO_CLIENT", "value": "http" },
        { "name": "FRU_MESSAGING_SMS_PROVIDER", "value": "http" },
        { "name": "FRU_MESSAGING_WHATSAPP_PROVIDER", "value": "stub" },
        { "name": "FRU_MESSAGING_WHATSAPP_ENABLED", "value": "false" },
        { "name": "FRU_MESSAGING_EMAIL_PROVIDER", "value": "stub" }
      ],
      "secrets": [
        { "name": "FRU_APP_PASSWORD", "valueFrom": "arn:aws:secretsmanager:${REGION}:${ACCOUNT}:secret:fru/staging/db/app" },
        { "name": "UQUDO_CLIENT_ID", "valueFrom": "arn:aws:secretsmanager:${REGION}:${ACCOUNT}:secret:fru/staging/uqudo:client_id::" },
        { "name": "UQUDO_CLIENT_SECRET", "valueFrom": "arn:aws:secretsmanager:${REGION}:${ACCOUNT}:secret:fru/staging/uqudo:client_secret::" },
        { "name": "UQUDO_AUTH_URL", "valueFrom": "arn:aws:ssm:${REGION}:${ACCOUNT}:parameter/fru/staging/uqudo/auth-url" },
        { "name": "UQUDO_API_BASE", "valueFrom": "arn:aws:ssm:${REGION}:${ACCOUNT}:parameter/fru/staging/uqudo/api-base" },
        { "name": "UQUDO_JWKS_URL", "valueFrom": "arn:aws:ssm:${REGION}:${ACCOUNT}:parameter/fru/staging/uqudo/jwks-url" },
        { "name": "UQUDO_ISSUER", "valueFrom": "arn:aws:ssm:${REGION}:${ACCOUNT}:parameter/fru/staging/uqudo/issuer" },
        { "name": "FRU_CIVIL_REGISTRY_ENDPOINT", "valueFrom": "arn:aws:ssm:${REGION}:${ACCOUNT}:parameter/fru/staging/civil-registry/endpoint" },
        { "name": "FRU_CORE_BANKING_ENDPOINT", "valueFrom": "arn:aws:ssm:${REGION}:${ACCOUNT}:parameter/fru/staging/core-banking/endpoint" },
        { "name": "FRU_MESSAGING_SMS_ENDPOINT", "valueFrom": "arn:aws:ssm:${REGION}:${ACCOUNT}:parameter/fru/staging/sms/endpoint" },
        { "name": "FRU_MESSAGING_SMS_SENDER_ID", "valueFrom": "arn:aws:ssm:${REGION}:${ACCOUNT}:parameter/fru/staging/sms/sender-id" },
        { "name": "FRU_MESSAGING_SMS_USERNAME", "valueFrom": "arn:aws:secretsmanager:${REGION}:${ACCOUNT}:secret:fru/staging/sms:username::" },
        { "name": "FRU_MESSAGING_SMS_PASSWORD", "valueFrom": "arn:aws:secretsmanager:${REGION}:${ACCOUNT}:secret:fru/staging/sms:password::" }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/${PREFIX}-backend",
          "awslogs-region": "${REGION}",
          "awslogs-stream-prefix": "backend"
        }
      }
    }
  ]
}
JSON
)
# NOT mktemp. This calls the WINDOWS aws.exe, which cannot open an MSYS path like
# /tmp/tmp.XXXX -- it resolves it literally and reports "No such file or directory". The same
# path-mangling cost 03-admin-access.sh its first AMI lookup. Write beside the script and hand
# the CLI a path it can actually open, converting when cygpath exists and leaving the path
# alone when it does not, so this works on Linux too.
TD_FILE="$HERE/.taskdef.json"
printf '%s' "$TASKDEF_JSON" > "$TD_FILE"
if command -v cygpath >/dev/null 2>&1; then
  TD_REF="file://$(cygpath -w "$TD_FILE" | tr '\\' '/')"
else
  TD_REF="file://$TD_FILE"
fi
TD_ARN=$(aws_ ecs register-task-definition --cli-input-json "$TD_REF" \
  --query 'taskDefinition.taskDefinitionArn' --output text) \
  && note "registered task definition revision" || fail "could not register the task definition"
rm -f "$TD_FILE"

# ---------------------------------------------------------------- service
echo
echo "== ECS service =="
if [ "$RC" -eq 0 ] && [ -n "${TD_ARN:-}" ]; then
  if aws_ ecs describe-services --cluster "$PREFIX" --services "${PREFIX}-backend" \
       --query 'services[?status==`ACTIVE`]' --output text | grep -q .; then
    aws_ ecs update-service --cluster "$PREFIX" --service "${PREFIX}-backend" \
      --task-definition "$TD_ARN" --force-new-deployment >/dev/null \
      && note "updated existing service to the new revision" || fail "could not update the service"
  else
    aws_ ecs create-service --cluster "$PREFIX" --service-name "${PREFIX}-backend" \
      --task-definition "$TD_ARN" --desired-count 1 --launch-type FARGATE \
      --network-configuration "awsvpcConfiguration={subnets=[${SUBNET_APP_A},${SUBNET_APP_B}],securityGroups=[${SG_ECS}],assignPublicIp=DISABLED}" \
      --load-balancers "targetGroupArn=${TG_ARN},containerName=backend,containerPort=8080" \
      --health-check-grace-period-seconds 120 \
      --tags key=Project,value=fru key=Environment,value=staging >/dev/null \
      && note "created service (desired count 1)" || fail "could not create the service"
  fi
fi

# ---------------------------------------------------------------- record
OUT="$HERE/app-tier-ids.env"
cat > "$OUT" <<EOF
# Generated by infra/aws/08-backend-service.sh — resource identifiers, not secrets. Gitignored.
CLUSTER=$PREFIX
SERVICE=${PREFIX}-backend
TG_ARN=$TG_ARN
ALB_ARN=$ALB_ARN
EXEC_ROLE=$EXEC_ROLE
IMAGE_TAG=$IMAGE_TAG
EOF
echo
note "wrote $OUT"

echo
if [ "$RC" -ne 0 ]; then
  echo "== 08 complete WITH FAILURES (exit 1) =="
else
  echo "== 08 complete. The service takes a few minutes to pass health checks. =="
fi
exit "$RC"
