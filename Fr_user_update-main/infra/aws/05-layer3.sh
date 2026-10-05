#!/usr/bin/env bash
#
# S7-06 — install Layer 3 of the audit append-only enforcement (R-028 / R-035).
# Runs ON the private admin host, as the RDS MAIN USER — never as fru_migrator, which is
# deliberately not superuser and therefore cannot create an event trigger at all.
#
# Applies db/post-migrate/01-audit-event-trigger.sql verbatim (delivered to /tmp/evt.sql by
# the caller, so this script never becomes a second, drifting copy of that SQL) and then runs
# the verification query the script's own header specifies.
#
# WHY THIS IS A NAMED STEP AND NOT A FOOTNOTE. flyway:migrate succeeds whether or not this
# ever runs. The app starts. Nothing errors. The only difference is that DDL against schema
# audit is unguarded. That is R-028's silent-skip shape, and it is exactly what a deployment
# runbook forgets. Expect EXACTLY 2 rows below; zero means Layer 3 is absent.
#
# Requires: DB_ENDPOINT, MASTER_SECRET_ARN, and /tmp/evt.sql.
set -uo pipefail
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-eu-central-1}"
umask 077

CREDS=$(aws secretsmanager get-secret-value --secret-id "$MASTER_SECRET_ARN" --query SecretString --output text)
PGUSER=$(printf '%s' "$CREDS" | jq -r .username); export PGUSER
PGPASSWORD=$(printf '%s' "$CREDS" | jq -r .password); export PGPASSWORD
unset CREDS
export PGHOST="$DB_ENDPOINT" PGPORT=5432 PGDATABASE=fru PGSSLMODE=require

echo "=== applying db/post-migrate/01-audit-event-trigger.sql as the RDS main user ==="
if psql -v ON_ERROR_STOP=1 -q -f /tmp/evt.sql 2>/tmp/l3err; then
  echo "APPLIED"
else
  echo "FAILED to apply:"; cat /tmp/l3err; rm -f /tmp/evt.sql /tmp/l3err; exit 1
fi

echo
echo "=== verification (the script's own gate): expect EXACTLY 2 rows, evtenabled = O ==="
psql -c "SELECT evtname, evtevent, evtenabled FROM pg_event_trigger WHERE evtname LIKE '%audit%' ORDER BY evtname;"
# READ the verdict, do not merely print it. This is computed in SQL, and until it was read a
# half-installed Layer 3 -- say the DDL guard present and the DROP guard absent -- printed
# "FAIL expected 2, found 1", then passed the DDL-refusal proof below (one guard is enough to
# refuse a CREATE TABLE), then exited 0. That is the exact defect class this session removed
# from 04-db-gates.sh, still live in the file next to it. Found by review.
L3_COUNT=$(psql -Atc "SELECT CASE WHEN count(*)=2 THEN 'PASS  Layer 3 present (2 event triggers)' ELSE 'FAIL  expected 2, found '||count(*) END FROM pg_event_trigger WHERE evtname LIKE '%audit%';" 2>&1)
echo "$L3_COUNT"
case "$L3_COUNT" in
  *PASS*) : ;;
  *) echo "FAIL  Layer 3 is not fully installed - refusing to report success"
     rm -f /tmp/evt.sql /tmp/l3err
     exit 1 ;;
esac

echo
echo "=== live proof: Layer 3 must REFUSE DDL against schema audit, as fru_migrator ==="
# A passing count query proves the triggers exist, not that they block anything. The
# guard is only real if a DDL statement is actually rejected — and it must be rejected
# for the OWNER of the schema, not merely for an unprivileged role, or it proves nothing.
# The pgpass file below holds a live credential in plaintext. Remove it on ANY exit, not
# just the happy path: this script runs without -e, so an abort between here and the rm
# would leave it on disk. Found by review.
trap 'rm -f ~/.pgpass' EXIT
MIGPW=$(aws secretsmanager get-secret-value --secret-id fru/staging/db/migrator --query SecretString --output text)
printf '%s:%s:%s:%s:%s\n' "$PGHOST" 5432 fru fru_migrator "$MIGPW" > ~/.pgpass
chmod 600 ~/.pgpass
unset MIGPW
OUT=$(PGPASSWORD= PGUSER=fru_migrator psql -v ON_ERROR_STOP=1 -c "CREATE TABLE audit.fru_layer3_probe (x int);" 2>&1)
rm -f ~/.pgpass
# Match the guard's OWN message, not the token ERROR. A loose match would also accept a
# failed connection, a bad ~/.pgpass, or an ordinary "permission denied for schema audit" —
# none of which prove the event trigger did anything. This assertion exists precisely because
# a count of pg_event_trigger rows is not enough, so it must not be satisfiable by an
# unrelated failure. Found by review.
if printf '%s' "$OUT" | grep -qi "requires a migration session\|block_audit_ddl"; then
  echo "PASS  DDL against schema audit was refused. Message:"
  printf '%s\n' "$OUT" | sed 's/^/      /' | head -4
else
  echo "FAIL  DDL against schema audit SUCCEEDED — Layer 3 is not enforcing:"
  printf '%s\n' "$OUT" | head -4
  psql -c "DROP TABLE IF EXISTS audit.fru_layer3_probe;" >/dev/null 2>&1
  # Exit non-zero. This script runs without -e, so printing FAIL and falling through to
  # "layer 3 step complete" reports an UNGUARDED audit schema as a successful invocation --
  # the same defect class 04-db-gates.sh carried. Found by review.
  rm -f /tmp/evt.sql /tmp/l3err
  exit 1
fi

rm -f /tmp/evt.sql /tmp/l3err
echo
echo "=== layer 3 step complete ==="
