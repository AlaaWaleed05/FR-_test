#!/usr/bin/env bash
#
# S7-03 — KMS key + RDS PostgreSQL 18 for the Fr_user_update stack (AWS, eu-central-1).
#
# Builds to docs/sessions/2026-09-06-aws-hosting-requirements.md:
#   M-2   RDS for PostgreSQL 18 — not Aurora, not self-managed on EC2.
#   M-9   Storage encryption under a CUSTOMER-MANAGED KMS key (R-026).
#         [DOC] RDS can only be encrypted AT CREATION, never after. There is no
#         do-over short of a snapshot-copy-and-restore of a database holding
#         identity-document images. This script is the only chance to get it right.
#   M-10  Backups inherit the instance key automatically — which is what makes M-9's
#         at-creation constraint load-bearing for backups too, since AD-004 put every
#         passport scan inside the database.
#   M-11  PubliclyAccessible=false, private subnet group, SG-to-SG only.
#   M-18/19  gp3. Storage grows but NEVER shrinks, so 20 GB is deliberate, not timid.
#
# Master credentials: --manage-master-user-password. AWS generates the password and
# stores it in Secrets Manager; it is never printed, never typed, and never passes
# through a prompt, a log or a session report. That is the only shape compatible with
# the standing no-secrets rule while these commands are run by an agent.
#
# NOT created here: the `fru` database itself. RDS accepts no initdb arguments, so the
# database it creates would carry whatever default locale it likes. Per M-3 we create
# ours explicitly with LOCALE_PROVIDER icu — see 04-db-gates.sh. Passing --db-name here
# would silently produce a database with the WRONG default collation, and nothing
# would fail: only Arabic sort order would differ from dev.
#
# Usage:  AWS_PROFILE=fru-deploy bash infra/aws/02-database.sh
set -euo pipefail

AWS="${AWS_CLI:-/c/Program Files/Amazon/AWSCLIV2/aws.exe}"
export AWS_PAGER=""
REGION="${AWS_REGION:-eu-central-1}"
PREFIX="fru-staging"

DB_ID="${PREFIX}-db"
DB_CLASS="db.t4g.micro"
DB_ENGINE_VERSION="18.6"
DB_STORAGE_GB=20
DB_STORAGE_MAX_GB=100
DB_MASTER_USER="fru_master"
BACKUP_RETENTION_DAYS=7

aws_() { "$AWS" --region "$REGION" "$@"; }

# Resource IDs from S7-02. Sourced here rather than assumed from the caller's environment:
# this script references $SG_RDS, and under `set -u` an unsourced run aborts with a bare
# "unbound variable" before create-db-instance. That is the safe direction, but a confusing
# failure, so the cause is named. Found by review after the live run had been driven with
# the file sourced externally — which made the script less reproducible than it claims to be.
. "$(dirname "$0")/network-ids.env"
: "${SG_RDS:?network-ids.env is missing SG_RDS - run infra/aws/01-network.sh first}"

# ---------------------------------------------------------------- KMS key (M-9)
echo "== KMS customer-managed key =="
KEY_ARN=$(aws_ kms describe-key --key-id "alias/${PREFIX}-rds" --query 'KeyMetadata.Arn' --output text 2>/dev/null || true)
if [ -z "$KEY_ARN" ] || [ "$KEY_ARN" = "None" ]; then
  KEY_ID=$(aws_ kms create-key \
    --description "Fr_user_update ${PREFIX}: RDS storage, snapshots and backups at rest (R-026 / M-9)" \
    --key-usage ENCRYPT_DECRYPT --key-spec SYMMETRIC_DEFAULT \
    --tags TagKey=Project,TagValue=fru TagKey=Environment,TagValue=staging \
    --query 'KeyMetadata.KeyId' --output text)
  aws_ kms create-alias --alias-name "alias/${PREFIX}-rds" --target-key-id "$KEY_ID"
  # Annual rotation: the bank inherits this key, and a never-rotating key is a finding.
  aws_ kms enable-key-rotation --key-id "$KEY_ID"
  KEY_ARN=$(aws_ kms describe-key --key-id "$KEY_ID" --query 'KeyMetadata.Arn' --output text)
fi
echo "KMS key ready"

# ---------------------------------------------------------------- RDS (M-2)
echo "== RDS PostgreSQL ${DB_ENGINE_VERSION} =="
if aws_ rds describe-db-instances --db-instance-identifier "$DB_ID" >/dev/null 2>&1; then
  echo "REFUSING: ${DB_ID} already exists. Encryption and locale are at-creation-only;" >&2
  echo "re-running against an existing instance would prove nothing." >&2
  exit 1
fi

aws_ rds create-db-instance \
  --db-instance-identifier "$DB_ID" \
  --db-instance-class "$DB_CLASS" \
  --engine postgres \
  --engine-version "$DB_ENGINE_VERSION" \
  --allocated-storage "$DB_STORAGE_GB" \
  --max-allocated-storage "$DB_STORAGE_MAX_GB" \
  --storage-type gp3 \
  --storage-encrypted \
  --kms-key-id "$KEY_ARN" \
  --master-username "$DB_MASTER_USER" \
  --manage-master-user-password \
  --db-subnet-group-name "${PREFIX}-db-subnets" \
  --vpc-security-group-ids "$SG_RDS" \
  --no-publicly-accessible \
  --backup-retention-period "$BACKUP_RETENTION_DAYS" \
  --no-multi-az \
  --deletion-protection \
  --auto-minor-version-upgrade \
  --copy-tags-to-snapshot \
  --enable-performance-insights \
  --performance-insights-retention-period 7 \
  --enable-cloudwatch-logs-exports postgresql \
  --tags Key=Project,Value=fru Key=Environment,Value=staging \
  --query 'DBInstance.{Id:DBInstanceIdentifier,Status:DBInstanceStatus,Encrypted:StorageEncrypted,Public:PubliclyAccessible}' \
  --output table

echo
echo "Creation started. It becomes available in roughly 5-10 minutes."
