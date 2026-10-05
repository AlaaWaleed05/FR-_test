#!/usr/bin/env bash
#
# S7-02 recovery — rebuild network-ids.env and admin-ids.env from what is actually in the
# account, by tag.
#
# WHY THIS EXISTS. 01-network.sh and 03-admin-access.sh write those two files, and both are
# gitignored (they hold resource IDs, which are not secrets, but they are machine-specific
# state and not source). 02-database.sh, 03-admin-access.sh and everything after them source
# the files and refuse to run without them. Meanwhile 01-network.sh REFUSES to run when the
# VPC already exists, and rightly so. So a fresh checkout on a second machine had no route
# back to a running stack at all: the one script that produces the IDs is the one script you
# must not run. Found by review.
#
# This is READ-ONLY. Every call below is a describe-*. It creates nothing, changes nothing,
# and deletes nothing -- run it against a live stack without ceremony.
#
# It works because 01-network.sh tags every resource it makes (Name=fru-staging-*, plus
# Project/Environment/ManagedBy) and 03-admin-access.sh tags the NAT, its EIP, the admin
# security group and the admin instance the same way. The Name tag is the lookup key, so
# these names are a contract between the two scripts and this one.
#
# Usage:  AWS_PROFILE=fru-deploy bash infra/aws/00-discover-ids.sh
#         AWS_PROFILE=fru-deploy bash infra/aws/00-discover-ids.sh --print   # stdout only
set -uo pipefail

AWS="${AWS_CLI:-/c/Program Files/Amazon/AWSCLIV2/aws.exe}"
export AWS_PAGER=""
export MSYS_NO_PATHCONV=1
REGION="${AWS_REGION:-eu-central-1}"
PREFIX="fru-staging"
HERE="$(cd "$(dirname "$0")" && pwd)"
PRINT_ONLY=0
[ "${1:-}" = "--print" ] && PRINT_ONLY=1

aws_() { "$AWS" --region "$REGION" "$@"; }

# NOT a shell variable. Every resolve() call below is a command substitution, which runs in a
# SUBSHELL -- an assignment there never reaches this shell, so the "refuse to write a partial
# file" guard at the end silently never fired and the script wrote empty values and exited 0.
# Found by review, and it is the same report-success-when-the-check-failed class this
# directory's other fixes exist to remove. A marker file crosses the subshell boundary.
MISSING_MARK="$(mktemp)"
rm -f "$MISSING_MARK"
trap 'rm -f "$MISSING_MARK"' EXIT
# A describe that matches nothing returns the literal "None" or an empty string, depending
# on the query. Both mean "not found", and neither may be written into an ids file as though
# it were an ID -- a later script would then fail with a confusing error somewhere else.
resolve() { # $1 = human label, $2... = aws args
  local label="$1"; shift
  local v
  v=$(aws_ "$@" 2>/dev/null | tr -d '\r')
  if [ -z "$v" ] || [ "$v" = "None" ]; then
    echo "  !! not found: $label" >&2
    : > "$MISSING_MARK"
    printf ''
    return 1
  fi
  # A tag filter can legitimately match more than one resource if something was created by
  # hand alongside the script's. Refuse rather than silently taking the first.
  if [ "$(printf '%s' "$v" | wc -w)" -gt 1 ]; then
    echo "  !! ambiguous: $label matched more than one resource ($v)" >&2
    : > "$MISSING_MARK"
    printf ''
    return 1
  fi
  printf '%s' "$v"
}

byname() { # $1 = ec2 subcommand, $2 = Name tag value, $3 = jmespath
  resolve "$2" ec2 "$1" --filters "Name=tag:Name,Values=$2" --query "$3" --output text
}

echo "== discovering ${PREFIX}-* in ${REGION} (read-only) =="

VPC_ID=$(byname describe-vpcs "${PREFIX}-vpc" 'Vpcs[].VpcId')
IGW_ID=$(byname describe-internet-gateways "${PREFIX}-igw" 'InternetGateways[].InternetGatewayId')

SUBNET_PUB_A=$(byname describe-subnets "${PREFIX}-public-a" 'Subnets[].SubnetId')
SUBNET_PUB_B=$(byname describe-subnets "${PREFIX}-public-b" 'Subnets[].SubnetId')
SUBNET_APP_A=$(byname describe-subnets "${PREFIX}-app-a"    'Subnets[].SubnetId')
SUBNET_APP_B=$(byname describe-subnets "${PREFIX}-app-b"    'Subnets[].SubnetId')
SUBNET_DB_A=$(byname  describe-subnets "${PREFIX}-db-a"     'Subnets[].SubnetId')
SUBNET_DB_B=$(byname  describe-subnets "${PREFIX}-db-b"     'Subnets[].SubnetId')

RT_PUB=$(byname describe-route-tables "${PREFIX}-rt-public" 'RouteTables[].RouteTableId')
RT_APP=$(byname describe-route-tables "${PREFIX}-rt-app"    'RouteTables[].RouteTableId')
RT_DB=$(byname  describe-route-tables "${PREFIX}-rt-db"     'RouteTables[].RouteTableId')

SG_ALB=$(byname describe-security-groups "${PREFIX}-alb-sg" 'SecurityGroups[].GroupId')
SG_ECS=$(byname describe-security-groups "${PREFIX}-ecs-sg" 'SecurityGroups[].GroupId')
SG_RDS=$(byname describe-security-groups "${PREFIX}-rds-sg" 'SecurityGroups[].GroupId')

# The DB subnet group is an RDS object with no EC2 Name tag -- its name IS its identifier,
# fixed by 01-network.sh. Confirm it exists rather than assuming the constant.
DB_SUBNET_GROUP=$(resolve "${PREFIX}-db-subnets" rds describe-db-subnet-groups \
  --db-subnet-group-name "${PREFIX}-db-subnets" \
  --query 'DBSubnetGroups[].DBSubnetGroupName' --output text)

# --- admin tier (03-admin-access.sh). The NAT filter must exclude deleted gateways: a
# torn-down NAT lingers in describe output for hours with state=deleted.
NAT_ID=$(resolve "${PREFIX}-nat" ec2 describe-nat-gateways \
  --filter "Name=tag:Name,Values=${PREFIX}-nat" "Name=state,Values=available,pending" \
  --query 'NatGateways[].NatGatewayId' --output text)
SG_ADMIN=$(byname describe-security-groups "${PREFIX}-admin-sg" 'SecurityGroups[].GroupId')
ADMIN_ID=$(resolve "${PREFIX}-admin" ec2 describe-instances \
  --filters "Name=tag:Name,Values=${PREFIX}-admin" "Name=instance-state-name,Values=pending,running" \
  --query 'Reservations[].Instances[].InstanceId' --output text)
# The IAM role carries no Name tag; 03-admin-access.sh names it deterministically.
ADMIN_ROLE=$(resolve "${PREFIX}-admin-role" iam get-role --role-name "${PREFIX}-admin-role" \
  --query 'Role.RoleName' --output text)

NET_OUT="$HERE/network-ids.env"
ADM_OUT="$HERE/admin-ids.env"

net_body() {
  cat <<EOF
# Rebuilt by infra/aws/00-discover-ids.sh from live tags — resource IDs, not secrets. Gitignored.
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
DB_SUBNET_GROUP=$DB_SUBNET_GROUP
EOF
}
adm_body() {
  cat <<EOF
# Rebuilt by infra/aws/00-discover-ids.sh from live tags — resource IDs, not secrets. Gitignored.
NAT_ID=$NAT_ID
SG_ADMIN=$SG_ADMIN
ADMIN_ROLE=$ADMIN_ROLE
ADMIN_ID=$ADMIN_ID
EOF
}

if [ -f "$MISSING_MARK" ]; then
  echo >&2
  echo "REFUSING to write: at least one resource above was not found or was ambiguous." >&2
  echo "A half-written ids file is worse than none — the next script would source it," >&2
  echo "pass its :? guards on the variables that ARE set, and fail somewhere unrelated." >&2
  echo >&2
  echo "What was resolved:" >&2
  net_body >&2
  adm_body >&2
  exit 1
fi

if [ "$PRINT_ONLY" -eq 1 ]; then
  net_body
  adm_body
  exit 0
fi

# Never clobber a file that is already right without leaving the old one recoverable.
for f in "$NET_OUT" "$ADM_OUT"; do
  [ -f "$f" ] && cp -p "$f" "$f.bak"
done
net_body > "$NET_OUT"
adm_body > "$ADM_OUT"
echo
echo "== wrote $NET_OUT and $ADM_OUT (previous versions saved as *.bak) =="
net_body
adm_body
