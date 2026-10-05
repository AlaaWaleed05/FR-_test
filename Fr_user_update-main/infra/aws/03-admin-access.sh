#!/usr/bin/env bash
#
# S7-03/S7-06 — the privileged path into the private database tier.
#
# WHY THIS EXISTS. M-11 makes the database unreachable from the internet, correctly.
# Something inside the VPC must therefore run the post-migrate steps and Flyway (M-7).
# The mapping's production shape for that is a one-shot ECS task, which needs ECR and a
# built image (S7-07). This host is the same idea one step earlier in the dependency
# order, and it is built to the same standard rather than as a convenience:
#
#   * PRIVATE subnet, no public IP, no Elastic IP. Not reachable from the internet.
#   * NO inbound rules at all. Its security group has zero ingress.
#   * No SSH keys anywhere. Access is AWS Systems Manager only, which is IAM-gated,
#     CloudTrail-audited and session-logged.
#   * Its own security group (`admin-sg`) is what RDS admits, distinct from the app's.
#     That mirrors the production split — the migrator identity is not the app identity.
#   * The master password is never handled here. The host reads it from Secrets Manager
#     with its own IAM role at the moment it is used, so the credential never leaves AWS
#     and never appears in a command, a log or a transcript.
#
# It is temporary and belongs on the teardown checklist.
#
# Also creates the NAT Gateway deferred by 01-network.sh: a private host needs an
# outbound path to reach Systems Manager. S7-07 needs it regardless.
#
# Usage:  AWS_PROFILE=fru-deploy bash infra/aws/03-admin-access.sh
set -euo pipefail

AWS="${AWS_CLI:-/c/Program Files/Amazon/AWSCLIV2/aws.exe}"
export AWS_PAGER=""
# Git Bash / MSYS rewrites any argument that looks like a POSIX path into a Windows one,
# which silently corrupts SSM parameter names such as /aws/service/... into
# C:/Program Files/Git/aws/service/... and fails as ParameterNotFound. Seen live.
export MSYS_NO_PATHCONV=1
REGION="${AWS_REGION:-eu-central-1}"
PREFIX="fru-staging"

aws_() { "$AWS" --region "$REGION" "$@"; }

# Resource IDs from S7-02, sourced rather than assumed (same reason as 02-database.sh).
. "$(dirname "$0")/network-ids.env"
for v in VPC_ID SUBNET_PUB_A SUBNET_APP_A RT_APP SG_RDS; do
  eval ": \"\${$v:?network-ids.env is missing $v - run infra/aws/01-network.sh first}\""
done

# ---------------------------------------------------------------- NAT gateway
echo "== NAT gateway =="
NAT_ID=$(aws_ ec2 describe-nat-gateways \
  --filter "Name=tag:Name,Values=${PREFIX}-nat" "Name=state,Values=available,pending" \
  --query 'NatGateways[0].NatGatewayId' --output text)
if [ "$NAT_ID" = "None" ] || [ -z "$NAT_ID" ]; then
  EIP_ALLOC=$(aws_ ec2 allocate-address --domain vpc \
    --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=${PREFIX}-nat-eip},{Key=Project,Value=fru}]" \
    --query 'AllocationId' --output text)
  NAT_ID=$(aws_ ec2 create-nat-gateway --subnet-id "$SUBNET_PUB_A" --allocation-id "$EIP_ALLOC" \
    --tag-specifications "ResourceType=natgateway,Tags=[{Key=Name,Value=${PREFIX}-nat},{Key=Project,Value=fru}]" \
    --query 'NatGateway.NatGatewayId' --output text)
  echo "waiting for NAT ${NAT_ID} ..."
  aws_ ec2 wait nat-gateway-available --nat-gateway-ids "$NAT_ID"
  aws_ ec2 create-route --route-table-id "$RT_APP" --destination-cidr-block 0.0.0.0/0 --nat-gateway-id "$NAT_ID" >/dev/null
fi
echo "NAT_ID=$NAT_ID"

# ---------------------------------------------------------------- admin security group
echo "== admin security group =="
SG_ADMIN=$(aws_ ec2 describe-security-groups --filters "Name=group-name,Values=${PREFIX}-admin-sg" "Name=vpc-id,Values=$VPC_ID" \
  --query 'SecurityGroups[0].GroupId' --output text)
if [ "$SG_ADMIN" = "None" ] || [ -z "$SG_ADMIN" ]; then
  SG_ADMIN=$(aws_ ec2 create-security-group --vpc-id "$VPC_ID" --group-name "${PREFIX}-admin-sg" \
    --description "Privileged DB admin/migration host. No ingress. SSM only." \
    --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=${PREFIX}-admin-sg},{Key=Project,Value=fru}]" \
    --query 'GroupId' --output text)
  # Deliberately NO ingress rules. Systems Manager is outbound-initiated.
  aws_ ec2 revoke-security-group-egress --group-id "$SG_ADMIN" \
    --ip-permissions "IpProtocol=-1,IpRanges=[{CidrIp=0.0.0.0/0}]" >/dev/null
  aws_ ec2 authorize-security-group-egress --group-id "$SG_ADMIN" \
    --ip-permissions "IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0,Description=ssm-and-secretsmanager}]" >/dev/null
  aws_ ec2 authorize-security-group-egress --group-id "$SG_ADMIN" \
    --ip-permissions "IpProtocol=tcp,FromPort=5432,ToPort=5432,UserIdGroupPairs=[{GroupId=${SG_RDS},Description=to-rds}]" >/dev/null
  aws_ ec2 authorize-security-group-egress --group-id "$SG_ADMIN" \
    --ip-permissions "IpProtocol=udp,FromPort=53,ToPort=53,IpRanges=[{CidrIp=10.0.0.0/16,Description=vpc-dns}]" >/dev/null
  aws_ ec2 authorize-security-group-egress --group-id "$SG_ADMIN" \
    --ip-permissions "IpProtocol=tcp,FromPort=53,ToPort=53,IpRanges=[{CidrIp=10.0.0.0/16,Description=vpc-dns-tcp}]" >/dev/null
  # RDS admits this SG as well as the app SG. Distinct identities, as in production.
  aws_ ec2 authorize-security-group-ingress --group-id "$SG_RDS" \
    --ip-permissions "IpProtocol=tcp,FromPort=5432,ToPort=5432,UserIdGroupPairs=[{GroupId=${SG_ADMIN},Description=from-admin-migration-host}]" >/dev/null
fi
echo "SG_ADMIN=$SG_ADMIN"

# ---------------------------------------------------------------- instance role
echo "== instance role =="
ROLE_NAME="${PREFIX}-admin-role"
if ! aws_ iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  aws_ iam create-role --role-name "$ROLE_NAME" \
    --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' \
    --tags Key=Project,Value=fru Key=Environment,Value=staging >/dev/null
  aws_ iam attach-role-policy --role-name "$ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
  # Two statements, and the second is deliberately broader than "the master secret only":
  #   1. READ the RDS-managed master secret. Its name is AWS-generated (`rds!db-<uuid>`), so it
  #      cannot be matched by name — the tag condition on the owning DB instance is what scopes it.
  #   2. READ AND WRITE every fru/staging secret. 04-db-gates.sh generates fru_migrator's password
  #      and files it (GetRandomPassword + CreateSecret/PutSecretValue), and 05-layer3.sh reads it
  #      back; both run on this host under this role. Granting only Get/Describe here — as the
  #      first version of this file did — leaves a script that cannot run as committed, which is
  #      worse than a slightly broader grant because it makes infra/aws/ a false record.
  #      Found by review: the live run had been unblocked with an out-of-band policy update that
  #      was never reflected here.
  aws_ iam put-role-policy --role-name "$ROLE_NAME" --policy-name read-db-secrets \
    --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["secretsmanager:GetSecretValue","secretsmanager:DescribeSecret"],"Resource":"*","Condition":{"StringLike":{"secretsmanager:ResourceTag/aws:rds:primaryDBInstanceArn":"*fru-staging-db*"}}},{"Effect":"Allow","Action":["secretsmanager:GetSecretValue","secretsmanager:DescribeSecret","secretsmanager:CreateSecret","secretsmanager:PutSecretValue","secretsmanager:TagResource"],"Resource":"arn:aws:secretsmanager:*:*:secret:fru/staging/*"},{"Effect":"Allow","Action":["secretsmanager:GetRandomPassword"],"Resource":"*"}]}'
  aws_ iam create-instance-profile --instance-profile-name "$ROLE_NAME" >/dev/null
  aws_ iam add-role-to-instance-profile --instance-profile-name "$ROLE_NAME" --role-name "$ROLE_NAME"
  echo "waiting for the instance profile to propagate ..."
  sleep 15
fi

# ---------------------------------------------------------------- the host
echo "== admin host =="
EXISTING=$(aws_ ec2 describe-instances \
  --filters "Name=tag:Name,Values=${PREFIX}-admin" "Name=instance-state-name,Values=pending,running" \
  --query 'Reservations[].Instances[].InstanceId' --output text)
if [ -n "$EXISTING" ]; then
  echo "already running: $EXISTING"; ADMIN_ID="$EXISTING"
else
  AMI_ID=$(aws_ ssm get-parameter \
    --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
    --query 'Parameter.Value' --output text)
  ADMIN_ID=$(aws_ ec2 run-instances \
    --image-id "$AMI_ID" --instance-type t4g.nano \
    --subnet-id "$SUBNET_APP_A" \
    --security-group-ids "$SG_ADMIN" \
    --iam-instance-profile "Name=${ROLE_NAME}" \
    --no-associate-public-ip-address \
    --metadata-options "HttpTokens=required,HttpEndpoint=enabled" \
    --user-data 'Content-Type: text/x-shellscript

#!/bin/bash
dnf install -y jq >/dev/null 2>&1
# AL2023 ships postgresql16 and postgresql15; there is no postgresql17 package, so listing it
# first is harmless but the ordering matters if that ever changes. A 16 client is fine against
# an 18 server for everything these scripts do. NOTE: user-data runs at first boot, which can
# precede the NAT route being usable — seen live, where every install failed silently and psql
# was simply absent. The gate scripts therefore must not assume this succeeded.
for p in postgresql16 postgresql15; do dnf install -y $p >/dev/null 2>&1 && break; done
psql --version > /var/log/fru-bootstrap.log 2>&1' \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${PREFIX}-admin},{Key=Project,Value=fru},{Key=Environment,Value=staging},{Key=Temporary,Value=true-see-teardown-checklist}]" \
    --query 'Instances[0].InstanceId' --output text)
fi
echo "ADMIN_ID=$ADMIN_ID"

OUT="$(dirname "$0")/admin-ids.env"
cat > "$OUT" <<EOF
# Generated by infra/aws/03-admin-access.sh — resource IDs, not secrets. Gitignored.
NAT_ID=$NAT_ID
SG_ADMIN=$SG_ADMIN
ADMIN_ROLE=$ROLE_NAME
ADMIN_ID=$ADMIN_ID
EOF
echo "== wrote $OUT =="
cat "$OUT"
