#!/usr/bin/env bash
#
# S7-02 — Networking for the Fr_user_update STAGING stack (AWS, eu-central-1).
#
# Builds to docs/sessions/2026-09-06-aws-hosting-requirements.md:
#   M-11  the database is unreachable from the public internet — private subnets,
#         and a security-group-TO-security-group rule for 5432, never a CIDR rule.
#   M-17  egress to Uqudo (443), the core-banking middleware (9494) and the Civil
#         Registry (5353). The non-standard ports are the trap: a default-deny egress
#         policy blocks 9494/5353 silently, so they are explicit rules here.
#
# This script is the record of what was provisioned. It contains no secrets and no
# account identifiers; it discovers everything it needs from the caller's profile.
# Resource IDs are written to infra/aws/network-ids.env (gitignored — account-specific).
#
# Deliberately NOT created here: the NAT Gateway. Nothing in this script needs egress, and a
# NAT Gateway bills ~$32/month from the moment it exists, so it is not created by S7-02.
# The app route table is therefore left with no default route, so the omission is visible.
#
# CORRECTION, same session: 03-admin-access.sh DID create the NAT Gateway, its Elastic IP and
# the 0.0.0.0/0 route on the app route table, because the private admin host it builds needs
# an outbound path to reach Systems Manager. The original plan deferred it to S7-07; the
# product owner's "treat this as production" instruction moved that host out of a public
# subnet, which pulled the NAT forward. Recorded here rather than left as a stale comment.
#
# Usage:  AWS_PROFILE=fru-deploy bash infra/aws/01-network.sh
set -euo pipefail

AWS="${AWS_CLI:-/c/Program Files/Amazon/AWSCLIV2/aws.exe}"
export AWS_PAGER=""
REGION="${AWS_REGION:-eu-central-1}"
PROJECT="fru"
ENVNAME="staging"
PREFIX="${PROJECT}-${ENVNAME}"

VPC_CIDR="10.0.0.0/16"
AZ_A="${REGION}a"
AZ_B="${REGION}b"

# Ports the backend must reach outbound (M-17).
PORT_HTTPS=443     # Uqudo, ECR, Secrets Manager, Parameter Store
PORT_COREBANK=9494 # core banking CheckAccount middleware
PORT_REGISTRY=5353 # Civil Registry
PORT_PG=5432

aws_() { "$AWS" --region "$REGION" "$@"; }

tagspec() { # $1 = resource type, $2 = Name value
  echo "ResourceType=$1,Tags=[{Key=Name,Value=$2},{Key=Project,Value=${PROJECT}},{Key=Environment,Value=${ENVNAME}},{Key=ManagedBy,Value=infra/aws/01-network.sh}]"
}

# ---------------------------------------------------------------- guard
existing=$(aws_ ec2 describe-vpcs \
  --filters "Name=tag:Name,Values=${PREFIX}-vpc" \
  --query 'Vpcs[].VpcId' --output text)
if [ -n "$existing" ]; then
  echo "REFUSING: ${PREFIX}-vpc already exists (${existing}). This script is not idempotent." >&2
  exit 1
fi

# ---------------------------------------------------------------- VPC
echo "== VPC =="
VPC_ID=$(aws_ ec2 create-vpc \
  --cidr-block "$VPC_CIDR" \
  --tag-specifications "$(tagspec vpc "${PREFIX}-vpc")" \
  --query 'Vpc.VpcId' --output text)
# RDS requires both of these to resolve its endpoint from inside the VPC.
aws_ ec2 modify-vpc-attribute --vpc-id "$VPC_ID" --enable-dns-support '{"Value":true}'
aws_ ec2 modify-vpc-attribute --vpc-id "$VPC_ID" --enable-dns-hostnames '{"Value":true}'
echo "VPC_ID=$VPC_ID"

# ---------------------------------------------------------------- subnets
# Three tiers, two AZs each. RDS requires a subnet group spanning >= 2 AZs even for a
# single-AZ instance, which is why the db tier is a pair and not one subnet.
echo "== subnets =="
mksubnet() { # $1 cidr  $2 az  $3 name
  aws_ ec2 create-subnet --vpc-id "$VPC_ID" --cidr-block "$1" --availability-zone "$2" \
    --tag-specifications "$(tagspec subnet "$3")" \
    --query 'Subnet.SubnetId' --output text
}
SUBNET_PUB_A=$(mksubnet 10.0.1.0/24  "$AZ_A" "${PREFIX}-public-a")
SUBNET_PUB_B=$(mksubnet 10.0.2.0/24  "$AZ_B" "${PREFIX}-public-b")
SUBNET_APP_A=$(mksubnet 10.0.11.0/24 "$AZ_A" "${PREFIX}-app-a")
SUBNET_APP_B=$(mksubnet 10.0.12.0/24 "$AZ_B" "${PREFIX}-app-b")
SUBNET_DB_A=$(mksubnet  10.0.21.0/24 "$AZ_A" "${PREFIX}-db-a")
SUBNET_DB_B=$(mksubnet  10.0.22.0/24 "$AZ_B" "${PREFIX}-db-b")

# ---------------------------------------------------------------- internet gateway
echo "== internet gateway =="
IGW_ID=$(aws_ ec2 create-internet-gateway \
  --tag-specifications "$(tagspec internet-gateway "${PREFIX}-igw")" \
  --query 'InternetGateway.InternetGatewayId' --output text)
aws_ ec2 attach-internet-gateway --vpc-id "$VPC_ID" --internet-gateway-id "$IGW_ID"

# ---------------------------------------------------------------- route tables
echo "== route tables =="
mkrt() { aws_ ec2 create-route-table --vpc-id "$VPC_ID" \
  --tag-specifications "$(tagspec route-table "$1")" \
  --query 'RouteTable.RouteTableId' --output text; }

RT_PUB=$(mkrt "${PREFIX}-rt-public")
aws_ ec2 create-route --route-table-id "$RT_PUB" --destination-cidr-block 0.0.0.0/0 --gateway-id "$IGW_ID" >/dev/null
aws_ ec2 associate-route-table --route-table-id "$RT_PUB" --subnet-id "$SUBNET_PUB_A" >/dev/null
aws_ ec2 associate-route-table --route-table-id "$RT_PUB" --subnet-id "$SUBNET_PUB_B" >/dev/null

# App tier: no default route from THIS script. 03-admin-access.sh adds the 0.0.0.0/0 route
# via the NAT Gateway it creates — see this file's header correction.
RT_APP=$(mkrt "${PREFIX}-rt-app")
aws_ ec2 associate-route-table --route-table-id "$RT_APP" --subnet-id "$SUBNET_APP_A" >/dev/null
aws_ ec2 associate-route-table --route-table-id "$RT_APP" --subnet-id "$SUBNET_APP_B" >/dev/null

# DB tier: no route off the VPC at all, in either direction. M-11.
RT_DB=$(mkrt "${PREFIX}-rt-db")
aws_ ec2 associate-route-table --route-table-id "$RT_DB" --subnet-id "$SUBNET_DB_A" >/dev/null
aws_ ec2 associate-route-table --route-table-id "$RT_DB" --subnet-id "$SUBNET_DB_B" >/dev/null

# ---------------------------------------------------------------- security groups
echo "== security groups =="
mksg() { aws_ ec2 create-security-group --vpc-id "$VPC_ID" --group-name "$1" --description "$2" \
  --tag-specifications "$(tagspec security-group "$1")" --query 'GroupId' --output text; }

SG_ALB=$(mksg "${PREFIX}-alb-sg" "Public ALB: TLS from the internet")
SG_ECS=$(mksg "${PREFIX}-ecs-sg" "Backend Fargate tasks")
SG_RDS=$(mksg "${PREFIX}-rds-sg" "RDS PostgreSQL: reachable only from the ECS task SG")

# --- ALB: 443 from the internet. 80 exists only to redirect to 443.
aws_ ec2 authorize-security-group-ingress --group-id "$SG_ALB" \
  --ip-permissions "IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0,Description=TLS}]" >/dev/null
aws_ ec2 authorize-security-group-ingress --group-id "$SG_ALB" \
  --ip-permissions "IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=0.0.0.0/0,Description=redirect-to-443}]" >/dev/null

# --- ECS ingress: only from the ALB, on the container port. SG-to-SG.
aws_ ec2 authorize-security-group-ingress --group-id "$SG_ECS" \
  --ip-permissions "IpProtocol=tcp,FromPort=8080,ToPort=8080,UserIdGroupPairs=[{GroupId=${SG_ALB},Description=from-alb}]" >/dev/null

# --- ECS egress: explicit (M-17). Replace the default allow-all rather than add beside it.
aws_ ec2 revoke-security-group-egress --group-id "$SG_ECS" \
  --ip-permissions "IpProtocol=-1,IpRanges=[{CidrIp=0.0.0.0/0}]" >/dev/null
aws_ ec2 authorize-security-group-egress --group-id "$SG_ECS" \
  --ip-permissions "IpProtocol=tcp,FromPort=${PORT_HTTPS},ToPort=${PORT_HTTPS},IpRanges=[{CidrIp=0.0.0.0/0,Description=uqudo-ecr-secretsmanager-ssm}]" >/dev/null
aws_ ec2 authorize-security-group-egress --group-id "$SG_ECS" \
  --ip-permissions "IpProtocol=tcp,FromPort=${PORT_COREBANK},ToPort=${PORT_COREBANK},IpRanges=[{CidrIp=0.0.0.0/0,Description=core-banking-CheckAccount}]" >/dev/null
aws_ ec2 authorize-security-group-egress --group-id "$SG_ECS" \
  --ip-permissions "IpProtocol=tcp,FromPort=${PORT_REGISTRY},ToPort=${PORT_REGISTRY},IpRanges=[{CidrIp=0.0.0.0/0,Description=civil-registry-GetCRSData}]" >/dev/null
aws_ ec2 authorize-security-group-egress --group-id "$SG_ECS" \
  --ip-permissions "IpProtocol=tcp,FromPort=${PORT_PG},ToPort=${PORT_PG},UserIdGroupPairs=[{GroupId=${SG_RDS},Description=to-rds}]" >/dev/null
# DNS to the VPC resolver. Revoking allow-all egress breaks name resolution otherwise —
# which fails as a connection timeout to Uqudo, not as an obvious DNS error.
aws_ ec2 authorize-security-group-egress --group-id "$SG_ECS" \
  --ip-permissions "IpProtocol=udp,FromPort=53,ToPort=53,IpRanges=[{CidrIp=${VPC_CIDR},Description=vpc-dns-resolver}]" >/dev/null
aws_ ec2 authorize-security-group-egress --group-id "$SG_ECS" \
  --ip-permissions "IpProtocol=tcp,FromPort=53,ToPort=53,IpRanges=[{CidrIp=${VPC_CIDR},Description=vpc-dns-resolver-tcp}]" >/dev/null

# --- RDS ingress: 5432 from the ECS SG and nothing else. M-11's core requirement.
aws_ ec2 authorize-security-group-ingress --group-id "$SG_RDS" \
  --ip-permissions "IpProtocol=tcp,FromPort=${PORT_PG},ToPort=${PORT_PG},UserIdGroupPairs=[{GroupId=${SG_ECS},Description=from-ecs-tasks}]" >/dev/null
# RDS needs no egress at all.
aws_ ec2 revoke-security-group-egress --group-id "$SG_RDS" \
  --ip-permissions "IpProtocol=-1,IpRanges=[{CidrIp=0.0.0.0/0}]" >/dev/null

# ---------------------------------------------------------------- DB subnet group
echo "== db subnet group =="
aws_ rds create-db-subnet-group \
  --db-subnet-group-name "${PREFIX}-db-subnets" \
  --db-subnet-group-description "Private DB subnets, no route to the internet" \
  --subnet-ids "$SUBNET_DB_A" "$SUBNET_DB_B" \
  --tags "Key=Project,Value=${PROJECT}" "Key=Environment,Value=${ENVNAME}" \
  --query 'DBSubnetGroup.DBSubnetGroupName' --output text

# ---------------------------------------------------------------- record
OUT="$(dirname "$0")/network-ids.env"
cat > "$OUT" <<EOF
# Generated by infra/aws/01-network.sh — resource IDs, not secrets. Gitignored.
REGION=$REGION
VPC_ID=$VPC_ID
IGW_ID=$IGW_ID
SUBNET_PUB_A=$SUBNET_PUB_A
SUBNET_PUB_B=$SUBNET_PUB_B
SUBNET_APP_A=$SUBNET_APP_A
SUBNET_APP_B=$SUBNET_APP_B
SUBNET_DB_A=$SUBNET_DB_A
SUBNET_DB_B=$SUBNET_DB_B
RT_PUB=$RT_PUB
RT_APP=$RT_APP
RT_DB=$RT_DB
SG_ALB=$SG_ALB
SG_ECS=$SG_ECS
SG_RDS=$SG_RDS
DB_SUBNET_GROUP=${PREFIX}-db-subnets
EOF
echo
echo "== wrote $OUT =="
cat "$OUT"
