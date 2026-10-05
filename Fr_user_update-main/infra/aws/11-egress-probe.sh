#!/usr/bin/env bash
#
# S7-12 pre-walk — can the backend's ACTUAL network path reach the external systems the
# acceptance walk depends on?
#
# WHY THIS CANNOT BE TESTED FROM THE ADMIN HOST. The admin host sits in
# `fru-staging-admin-sg`, whose egress is 443 + DNS + 5432 only. A connect test to the Civil
# Registry's 5353 from there times out because of OUR security group, not the bank's
# firewall, and reads exactly like an unreachable endpoint. This probe runs in
# `fru-staging-ecs-sg`, in the app subnet, behind the same NAT Gateway and the same Elastic
# IP as the running backend, so a timeout here is a fact about the far end.
#
# SAFE BY CONSTRUCTION, the 10-s711-selector-probe.sh pattern: a SEPARATE task definition
# family built from the live one, ONE task outside the service, deregistered afterwards. The
# service, its task definition, its target group and its desired count are never touched.
#
# The probe task definition STRIPS the `secrets` block. Opening a TCP connection needs no
# credential, and a shell running in a container whose environment holds the Uqudo client
# secret is a leak surface this probe has no reason to create.
#
# THE CONTROL MATTERS. Uqudo:443 is probed alongside the registry. Without it a timeout on
# 5353 proves only "this probe could not reach something", which is equally consistent with a
# broken probe, a missing NAT route, or a DNS failure.
#
# Usage:  AWS_PROFILE=fru-deploy bash infra/aws/11-egress-probe.sh
set -uo pipefail

AWS="${AWS_CLI:-/c/Program Files/Amazon/AWSCLIV2/aws.exe}"
export AWS_PAGER=""
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'
REGION="${AWS_REGION:-eu-central-1}"
PREFIX="fru-staging"
PROBE_FAMILY="${PREFIX}-egress-probe"
HERE="$(cd "$(dirname "$0")" && pwd)"

. "$HERE/network-ids.env"
for v in SUBNET_APP_A SG_ECS; do
  eval ": \"\${$v:?network-ids.env is missing $v}\""
done

aws_() { "$AWS" --region "$REGION" "$@"; }
cli_path() {
  if command -v cygpath >/dev/null 2>&1; then echo "file://$(cygpath -w "$1" | tr '\\' '/')"
  else echo "file://$1"; fi
}

# Read the endpoints from Parameter Store rather than retyping them, so the probe cannot
# drift from what the task definition actually injects.
CR_URL=$(aws_ ssm get-parameter --name /fru/staging/civil-registry/endpoint --query 'Parameter.Value' --output text)
AUTH_URL=$(aws_ ssm get-parameter --name /fru/staging/uqudo/auth-url --query 'Parameter.Value' --output text)
API_BASE=$(aws_ ssm get-parameter --name /fru/staging/uqudo/api-base --query 'Parameter.Value' --output text)
for v in CR_URL AUTH_URL API_BASE; do
  eval ": \"\${$v:?Parameter Store did not return a value for $v}\""
done

# An endpoint that has no Parameter Store entry yet can still be probed, so that "can the
# backend's network reach it" is answerable BEFORE deciding to wire it in. Pass it as
# PROBE_EXTRA="label=https://host:port/path".
EXTRA_LABEL=""; EXTRA_URL=""
if [ -n "${PROBE_EXTRA:-}" ]; then
  EXTRA_LABEL="${PROBE_EXTRA%%=*}"; EXTRA_URL="${PROBE_EXTRA#*=}"
  echo "   extra target   : $EXTRA_LABEL -> $EXTRA_URL"
fi

echo "== targets, taken from Parameter Store =="
echo "   civil registry : $CR_URL   (the walk's real-CR dependency)"
echo "   uqudo auth     : $AUTH_URL   (control)"
echo "   uqudo api      : $API_BASE   (control)"
echo

echo "== building a probe task definition from the live one =="
LIVE=$(aws_ ecs describe-task-definition --task-definition "${PREFIX}-backend" \
        --query 'taskDefinition' --output json 2>/dev/null)
if [ -z "$LIVE" ] || [ "$LIVE" = "None" ]; then
  echo "REFUSING: no ${PREFIX}-backend task definition exists." >&2; exit 1
fi

PROBE_FILE="$HERE/.egress-probe.json"
export PROBE_FAMILY CR_URL AUTH_URL API_BASE EXTRA_LABEL EXTRA_URL
printf '%s' "$LIVE" | python -c '
import json, os, sys
from urllib.parse import urlparse

def hostport(url, default_port):
    u = urlparse(url)
    if not u.hostname:
        sys.stderr.write("REFUSING: could not parse a host out of " + url + "\n"); sys.exit(2)
    return u.hostname, (u.port or default_port)

targets = [
    ("civil-registry",) + hostport(os.environ["CR_URL"], 443),
    ("uqudo-auth",) + hostport(os.environ["AUTH_URL"], 443),
    ("uqudo-api",) + hostport(os.environ["API_BASE"], 443),
]
if os.environ.get("EXTRA_URL"):
    targets.append((os.environ["EXTRA_LABEL"],) + hostport(os.environ["EXTRA_URL"], 443))

lines = ["echo PROBE-START"]
for label, host, port in targets:
    lines.append(
        "ip=$(nslookup " + host + " 2>/dev/null | awk \x27/^Address/{a=$NF} END{print a}\x27); "
        "if nc -w 10 " + host + " " + str(port) + " </dev/null >/dev/null 2>&1; "
        "then s=OPEN; else s=UNREACHABLE; fi; "
        "echo \"RESULT " + label + " " + host + ":" + str(port) + " dns=${ip:-FAIL} tcp=$s\""
    )
lines.append("echo PROBE-END")
probe_sh = "\n".join(lines)

td = json.load(sys.stdin)
keep = ("networkMode","requiresCompatibilities","cpu","memory","runtimePlatform",
        "executionRoleArn","taskRoleArn","volumes","containerDefinitions")
out = {k: td[k] for k in keep if k in td}
out["family"] = os.environ["PROBE_FAMILY"]
if not out.get("containerDefinitions"):
    sys.stderr.write("REFUSING: the live task definition has no container definition.\n"); sys.exit(2)
for c in out["containerDefinitions"]:
    c.pop("secrets", None)      # a TCP connect needs no credential
    c.pop("healthCheck", None)
    c.pop("portMappings", None)
    c["environment"] = []       # the probe binds no Spring property
    c["entryPoint"] = ["/bin/sh", "-c"]
    c["command"] = [probe_sh]
    if "secrets" in c or c["environment"]:
        sys.stderr.write("REFUSING: the strip did not take.\n"); sys.exit(2)
json.dump(out, sys.stdout, indent=2)
' > "$PROBE_FILE" || { echo "could not build the probe task definition" >&2; rm -f "$PROBE_FILE"; exit 1; }

PROBE_ARN=$(aws_ ecs register-task-definition --cli-input-json "$(cli_path "$PROBE_FILE")" \
  --query 'taskDefinition.taskDefinitionArn' --output text) || {
    echo "could not register the probe task definition" >&2; rm -f "$PROBE_FILE"; exit 1; }
rm -f "$PROBE_FILE"
echo "  registered $PROBE_FAMILY"

echo
echo "== one probe task: ECS security group, app subnet, same NAT as the backend =="
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

STATUS=""
for _ in $(seq 1 40); do
  STATUS=$(aws_ ecs describe-tasks --cluster "$PREFIX" --tasks "$TASK_ARN" \
            --query 'tasks[0].lastStatus' --output text 2>/dev/null)
  echo "  lastStatus=$STATUS"
  [ "$STATUS" = "STOPPED" ] && break
  sleep 15
done
if [ "$STATUS" != "STOPPED" ]; then
  # Nothing reaps a run-task task outside a service; one left running bills silently.
  aws_ ecs stop-task --cluster "$PREFIX" --task "$TASK_ARN" --reason "egress probe overran" >/dev/null 2>&1
  echo "  NOTE: the probe overran and was stopped by hand" >&2
fi

echo
echo "== results, from the task's own log stream =="
RC=0
# CloudWatch ingestion lags the task's own STOPPED transition by a few seconds, so a single read
# here reports "no verdict" for a probe that in fact ran perfectly -- observed at S7-12, where the
# core-banking result was sitting in the stream moments after the script had already given up.
# Retry until the terminator appears rather than treating a slow log as a failed probe.
LOG=""
for _ in $(seq 1 10); do
  LOG=$(aws_ logs get-log-events --log-group-name "/ecs/${PREFIX}-backend" \
         --log-stream-name "backend/backend/${TASK_ID}" --limit 200 \
         --query 'events[].message' --output text 2>/dev/null)
  printf '%s' "$LOG" | grep -q "PROBE-END" && break
  sleep 5
done
if ! printf '%s' "$LOG" | grep -q "PROBE-END"; then
  echo "FAIL  the probe did not run to completion -- its own output is missing, so there is no verdict" >&2
  printf '%s' "$LOG" | tail -c 900 | sed 's/^/      /'
  RC=1
else
  printf '%s' "$LOG" | tr '\t' '\n' | grep '^RESULT ' | sed 's/^/  /'
  printf '%s' "$LOG" | tr '\t' '\n' | grep -q 'uqudo-auth .*tcp=OPEN' || {
    echo "  the Uqudo CONTROL failed -- suspect the probe, the NAT path or DNS, not the far end" >&2; RC=1; }
  printf '%s' "$LOG" | tr '\t' '\n' | grep -q 'civil-registry .*tcp=OPEN' || {
    echo "  BLOCKER  the Civil Registry endpoint is NOT reachable from the backend's own network path" >&2; RC=2; }
fi

echo
aws_ ecs deregister-task-definition --task-definition "$PROBE_ARN" >/dev/null 2>&1 \
  && echo "  probe task definition deregistered" \
  || echo "  NOTE: could not deregister $PROBE_ARN -- remove it by hand"
exit "$RC"
