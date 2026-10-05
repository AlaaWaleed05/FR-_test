#!/usr/bin/env bash
#
# Branch tests for infra/aws/04-db-gates.sh.
#
# WHY THIS EXISTS. 04-db-gates.sh runs exactly once, against a live account, and creates
# the database it is named after checking. Re-running it to test a change is not an option:
# it rotates fru_migrator's live password. So its failure branches were never executed by
# anything -- which is how a gate that printed PASS when its own query errored survived
# review twice. This harness executes every branch with stub psql/aws/jq earlier on PATH:
# no network, no credential, no account. The stubs are the only psql/aws/jq that resolve.
#
# Usage:  bash infra/aws/test/run-gate-tests.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STUBS="$HERE/stubs"
SCRIPT="$HERE/../04-db-gates.sh"
chmod +x "$STUBS"/* 2>/dev/null || true

PASSED=0; FAILED=0
OUT=""; RC=0

run() { # $@ = STUB_x=y assignments
  OUT=$(env PATH="$STUBS:$PATH" \
           DB_ENDPOINT=stub.db.invalid \
           MASTER_SECRET_ARN=arn:aws:secretsmanager:eu-central-1:000000000000:secret:stub \
           "$@" bash "$SCRIPT" 2>&1)
  RC=$?
}

want_rc() { # $1 = expected rc, $2 = label
  if [ "$RC" -eq "$1" ]; then echo "  ok    exit $RC            $2"; PASSED=$((PASSED+1))
  else echo "  FAIL  exit $RC, wanted $1   $2"; FAILED=$((FAILED+1)); fi
}
want_has() { # $1 = substring, ${2:-} = label
  case "$OUT" in *"$1"*) echo "  ok    says '$1'"; PASSED=$((PASSED+1)) ;;
  *) echo "  FAIL  missing '$1'   ${2:-}"; FAILED=$((FAILED+1)) ;; esac
}
want_lacks() { # $1 = substring, ${2:-} = label
  case "$OUT" in *"$1"*) echo "  FAIL  wrongly says '$1'   ${2:-}"; FAILED=$((FAILED+1)) ;;
  *) echo "  ok    never says '$1'"; PASSED=$((PASSED+1)) ;; esac
}

echo "=== 1. happy path: every gate passes ==="
run STUB_CREATEDB=ok
want_rc 0 "a clean provisioning run"
want_has "=== gates complete ==="
want_lacks "WITH FAILURES"

echo
echo "=== 2. M-3a verdict says FAIL (SQL-computed, previously never read) ==="
run STUB_M3A=fail
want_rc 1 "a FAIL verdict must not exit 0"
want_has "FAIL  M-3a: the check above reported FAIL"

echo
echo "=== 3. M-3a query ERRORS: no PASS and no FAIL is produced at all ==="
run STUB_M3A=error
want_rc 1 "absence of a verdict is a failure, not a pass"
want_has "no verdict was produced"

echo
echo "=== 4. M-3b: database default provider is not ICU ==="
run STUB_M3B=fail
want_rc 1 "wrong locale provider must fail the run"
want_has "FAIL default provider is NOT ICU"

echo
echo "=== 5. M-3c THE FALSE-PASS: only the ar-x-icu query fails ==="
echo "    (empty ICU order vs populated C order -- the two 'differ')"
run STUB_ICU=error
want_rc 1 "the condition M-3c exists to detect must fail the run"
want_lacks "PASS  ICU collation demonstrably changes ordering"
want_has "a collation query returned nothing"

echo
echo "=== 6. M-3c: ICU orders identically to codepoint order ==="
run STUB_ICU=same
want_rc 1 "nominal-only ICU must fail the run"
want_has "ICU is not collating"

echo
echo "=== 7. M-4 STOP GATE: statement succeeds but no trigger exists ==="
run STUB_M4=fail
want_rc 1 "the STOP gate must stop the run"
want_has "FAIL  M-4: the check above reported FAIL"

echo
echo "=== 8. M-4 STOP GATE: CREATE EVENT TRIGGER refused outright ==="
run STUB_EVT=refused
want_rc 1 "the refusal path already exited 1; guarding the regression"
want_has "this is the STOP condition"

echo
echo "=== 9. first creation of the migrator secret fails ==="
echo "    (no role may be created whose password is stored nowhere)"
run STUB_SECRET_EXISTS=no STUB_CREATE=fail
want_rc 1 "an unfiled password must abort before CREATE ROLE"
want_has "refusing to create a role whose password is stored nowhere"
want_lacks "PASS  CREATE ROLE fru_migrator"

echo
echo "=== 10. rotation of an existing migrator secret fails ==="
run STUB_SECRET_EXISTS=yes STUB_PUT=fail
want_rc 1 "the rotation path was already guarded; guarding the regression"
want_has "could not store the migrator password"

echo
echo "=== 11. password generation returns empty ==="
run STUB_PWGEN=empty
want_rc 1 "no passwordless privileged role, ever"
want_has "refusing to create a passwordless role"

echo
echo "=== 12. role creation refused, and ownership transfer refused ==="
run STUB_ROLE=refused
want_rc 1 "a refused CREATE ROLE must not exit 0"
want_has "FAIL  cannot create login roles"
run STUB_OWNER=refused
want_rc 1 "a refused ownership transfer must not exit 0"
want_has "FAIL  ownership transfer refused"

echo
echo "=== 13. idempotent re-run: database and role already exist ==="
run STUB_CREATEDB=exists STUB_ROLE=exists STUB_SECRET_EXISTS=yes
want_rc 0 "SKIP branches are not failures"
want_has "SKIP  fru already exists"
want_has "SKIP  fru_migrator existed; password reset"

echo
echo "=== 14. the probe event trigger fails to drop ==="
echo "    (it would otherwise fire on every DDL in fru while the transcript said it was gone)"
run STUB_DROP=leftover
want_rc 1 "an undropped probe trigger must not be announced as removed"
want_has "the probe event trigger is STILL PRESENT"
want_lacks "probe objects removed"

echo
echo "=== 15. REGRESSION: the same scenario 5 against the pre-fix script (95f5b4d) ==="
OLD=$(mktemp); trap 'rm -f "$OLD"' EXIT
if git -C "$HERE" show 95f5b4d:infra/aws/04-db-gates.sh > "$OLD" 2>/dev/null; then
  OLD_OUT=$(env PATH="$STUBS:$PATH" \
                DB_ENDPOINT=stub.db.invalid \
                MASTER_SECRET_ARN=arn:aws:secretsmanager:eu-central-1:000000000000:secret:stub \
                STUB_ICU=error bash "$OLD" 2>&1)
  OLD_RC=$?
  echo "    pre-fix exit code : $OLD_RC"
  echo "    pre-fix verdict   : $(printf '%s\n' "$OLD_OUT" | grep -E 'ICU collation|not collating' || echo '(none)')"
  if [ "$OLD_RC" -eq 0 ] && printf '%s' "$OLD_OUT" | grep -q "PASS  ICU collation demonstrably changes ordering"; then
    echo "  ok    the pre-fix script printed PASS and exited 0 on a failed ICU query"
    echo "        -- the fixed script fails the identical scenario in test 5"
    PASSED=$((PASSED+1))
  else
    echo "  FAIL  expected the pre-fix script to show the false PASS; it did not"
    FAILED=$((FAILED+1))
  fi
else
  echo "  skip  commit 95f5b4d not reachable from here"
fi

echo
echo "================================================"
echo "  passed: $PASSED   failed: $FAILED"
echo "================================================"
[ "$FAILED" -eq 0 ]
