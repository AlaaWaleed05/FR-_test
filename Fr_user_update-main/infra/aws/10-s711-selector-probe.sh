#!/usr/bin/env bash
#
# S7-11 — prove ON FARGATE that a backend task with a stub/real selector UNSET refuses to
# start, rather than starting with a default nobody chose.
#
# WHY THIS NEEDS ITS OWN RUN, WHEN A TEST ALREADY ASSERTS IT.
# CivilRegistryClientConfigurationTest already proves the guard in a JVM. What it cannot prove
# is the dimension that actually differs on AWS: the value arrives as an ECS-injected
# ENVIRONMENT VARIABLE mapped onto a HYPHENATED property key. `fru.core-banking.client` and
# `fru.civil-registry.client` contain hyphens, and Spring's relaxed binding will happily
# resolve FRU_CORE_BANKING_CLIENT onto them from the environment. That is the hazard: a
# deployment could satisfy the property by accident, through a mechanism nobody wrote down,
# and the explicit `${FRU_CORE_BANKING_CLIENT}` binding in application-aws.properties exists
# precisely to make the mapping visible instead. This probe checks the binding fails CLOSED
# when the variable is genuinely absent, in the real injection path.
#
# SAFE BY CONSTRUCTION. It registers a SEPARATE task definition family, runs ONE task outside
# the service, and deregisters afterwards. It never touches fru-staging-backend's service,
# task definition, target group or desired count.
#
# Usage:  AWS_PROFILE=fru-deploy bash infra/aws/10-s711-selector-probe.sh
set -uo pipefail

AWS="${AWS_CLI:-/c/Program Files/Amazon/AWSCLIV2/aws.exe}"
export AWS_PAGER=""
export MSYS_NO_PATHCONV=1
REGION="${AWS_REGION:-eu-central-1}"
PREFIX="fru-staging"
PROBE_FAMILY="${PREFIX}-s711-probe"
HERE="$(cd "$(dirname "$0")" && pwd)"

. "$HERE/network-ids.env"
for v in SUBNET_APP_A SG_ECS; do
  eval ": \"\${$v:?network-ids.env is missing $v}\""
done

aws_() { "$AWS" --region "$REGION" "$@"; }
cli_path() { if command -v cygpath >/dev/null 2>&1; then echo "file://$(cygpath -w "$1" | tr '\\' '/')"; else echo "file://$1"; fi; }

echo "== building a probe task definition from the live one, minus ONE selector =="
LIVE=$(aws_ ecs describe-task-definition --task-definition "${PREFIX}-backend" \
        --query 'taskDefinition' --output json 2>/dev/null)
if [ -z "$LIVE" ] || [ "$LIVE" = "None" ]; then
  echo "REFUSING: no ${PREFIX}-backend task definition exists. Run 08 first." >&2
  exit 1
fi

PROBE_FILE="$HERE/.s711-probe.json"
printf '%s' "$LIVE" | python -c "
import json, sys
td = json.load(sys.stdin)
# Keep only the fields register-task-definition accepts; describe returns read-only ones too.
keep = ('networkMode','requiresCompatibilities','cpu','memory','runtimePlatform',
        'executionRoleArn','taskRoleArn','volumes','containerDefinitions')
out = {k: td[k] for k in keep if k in td}
out['family'] = '${PROBE_FAMILY}'
removed = False
for c in out.get('containerDefinitions', []):
    env = c.get('environment', [])
    before = len(env)
    c['environment'] = [e for e in env if e['name'] != 'FRU_CORE_BANKING_CLIENT']
    if len(c['environment']) != before:
        removed = True
if not removed:
    sys.stderr.write('REFUSING: FRU_CORE_BANKING_CLIENT was not present to remove -- the probe would prove nothing.\n')
    sys.exit(2)
json.dump(out, sys.stdout, indent=2)
" > "$PROBE_FILE" || { echo "could not build the probe task definition" >&2; rm -f "$PROBE_FILE"; exit 1; }

echo "  removed FRU_CORE_BANKING_CLIENT; every other variable left exactly as the service has it"

PROBE_ARN=$(aws_ ecs register-task-definition --cli-input-json "$(cli_path "$PROBE_FILE")" \
  --query 'taskDefinition.taskDefinitionArn' --output text) || {
    echo "could not register the probe task definition" >&2; rm -f "$PROBE_FILE"; exit 1; }
rm -f "$PROBE_FILE"
echo "  registered probe family: $PROBE_FAMILY"

echo
echo "== running one probe task (outside the service) =="
TASK_ARN=$(aws_ ecs run-task --cluster "$PREFIX" --task-definition "$PROBE_ARN" \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[${SUBNET_APP_A}],securityGroups=[${SG_ECS}],assignPublicIp=DISABLED}" \
  --query 'tasks[0].taskArn' --output text)
if [ -z "$TASK_ARN" ] || [ "$TASK_ARN" = "None" ]; then
  echo "FAIL  could not start the probe task" >&2
  aws_ ecs deregister-task-definition --task-definition "$PROBE_ARN" >/dev/null 2>&1
  exit 1
fi
TASK_ID="${TASK_ARN##*/}"
echo "  task started"

echo
echo "== waiting for it to STOP (a start would itself be the failure) =="
STATUS=""
for _ in $(seq 1 40); do
  STATUS=$(aws_ ecs describe-tasks --cluster "$PREFIX" --tasks "$TASK_ARN" \
            --query 'tasks[0].lastStatus' --output text 2>/dev/null)
  echo "  lastStatus=$STATUS"
  [ "$STATUS" = "STOPPED" ] && break
  sleep 15
done

RC=0
if [ "$STATUS" != "STOPPED" ]; then
  echo "FAIL  the probe task did not stop. A task missing a selector MUST NOT run." >&2
  RC=1
  # Nothing reaps a run-task task outside a service: it would run, and bill, until stopped by
  # hand. That matters most in exactly this branch -- a backend that STARTED without a
  # selector is the outcome this probe exists to catch, and leaving it running would be a
  # second fault on top of the first. Found by review.
  aws_ ecs stop-task --cluster "$PREFIX" --task "$TASK_ARN"     --reason "s711 probe did not self-terminate" >/dev/null 2>&1     && echo "  stopped the runaway probe task"     || echo "  NOTE: could not stop the probe task - stop it by hand" >&2
fi

EXITCODE=$(aws_ ecs describe-tasks --cluster "$PREFIX" --tasks "$TASK_ARN" \
            --query 'tasks[0].containers[0].exitCode' --output text 2>/dev/null)
echo "  container exit code: $EXITCODE"
[ "$EXITCODE" = "1" ] || { echo "FAIL  expected exit code 1 from a refused startup" >&2; RC=1; }

echo
echo "== the reason, from the task's own log stream =="
# The assertion is on the MESSAGE, not merely on a non-zero exit: a task can die for a dozen
# reasons that have nothing to do with the selector, and "it exited 1" would accept all of
# them. Session 1's review made exactly this correction to the Layer 3 proof.
LOG=$(aws_ logs get-log-events --log-group-name "/ecs/${PREFIX}-backend" \
       --log-stream-name "backend/backend/${TASK_ID}" --limit 200 \
       --query 'events[].message' --output text 2>/dev/null)
if printf '%s' "$LOG" | grep -q "Could not resolve placeholder 'FRU_CORE_BANKING_CLIENT'"; then
  echo "  PASS  refused with: Could not resolve placeholder 'FRU_CORE_BANKING_CLIENT'"
  printf '%s' "$LOG" | grep -o "Could not resolve placeholder 'FRU_CORE_BANKING_CLIENT'[^\"]*" | head -1 | sed 's/^/        /'
else
  echo "FAIL  the task stopped, but not for the reason this probe exists to prove." >&2
  echo "      Without the selector message this proves only that something went wrong." >&2
  printf '%s' "$LOG" | tail -c 800 | sed 's/^/      /'
  RC=1
fi

echo
echo "== cleanup =="
aws_ ecs deregister-task-definition --task-definition "$PROBE_ARN" >/dev/null 2>&1 \
  && echo "  probe task definition deregistered" \
  || echo "  NOTE: could not deregister $PROBE_ARN -- remove it by hand"

echo
if [ "$RC" -ne 0 ]; then
  echo "== S7-11 probe FAILED (exit 1) =="
else
  echo "== S7-11 probe PASSED: an unset selector refuses to start on Fargate =="
fi
exit "$RC"
