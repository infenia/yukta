#!/usr/bin/env bash
# Use case: production release with a human approval gate, a smoke check and automatic rollback.
# This script installs release.sh (the stand-in for your real commands) and plays the approver:
# it waits for the pending approval, then sends the signal.
# Needs a server that allows `sh` (yukta.process.allowed-commands=sh, or YUKTA_PROCESS_ALLOW_ANY=true).
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
for p in PROCESS_EXECUTOR WAIT_FOR_SIGNAL FILE_WRITE OUTCOME_TERMINAL; do require_plugin "$p"; done

SID=$(jq -r .sessionId session.json)
STATE=/tmp/yukta-example-release-approval   # the paths in session.json point here
rm -rf "$STATE"; mkdir -p "$STATE"; : >"$STATE/notices.log"
cp release.sh "$STATE/release.sh"
echo "v1.4.0" >"$STATE/live"   # what runs in production before the release

# --- helpers (local to this example) -------------------------------------------------------
require_process() {  # SKIP (exit 3) when the server refuses to run commands
  local probe='{"sessionId":"example-uc-probe","name":"probe","description":"probe","initiator":"examples","projectPath":"/tmp","workflows":{"p":{"workflowId":"p","description":"p","nodes":[{"nodeId":"t","type":"CONSTANT_SOURCE","config":{}},{"nodeId":"e","type":"PROCESS_EXECUTOR","config":{"command":["sh","-c","echo ok"]}},{"nodeId":"x","type":"CONSOLE_TERMINAL","config":{}}],"edges":[{"source":"t","target":"e"},{"source":"e","target":"x"}]}}}'
  local eid st i
  api POST /api/sessions "$probe" >/dev/null
  eid=$(api POST /api/workflow/start '{"sessionId":"example-uc-probe","workflowId":"p"}' | jq -r .data.executionId)
  for ((i = 0; i < 20; i++)); do
    st=$(api GET "/api/executions/$eid" 2>/dev/null | jq -r .data.status || true)
    [[ $st =~ $TERMINAL_STATUSES ]] && break; sleep 1
  done
  api DELETE /api/sessions/example-uc-probe >/dev/null 2>&1 || true
  if [[ $st != SUCCESS ]]; then
    echo "SKIP: the server does not run commands (set yukta.process.allowed-commands=sh or YUKTA_PROCESS_ALLOW_ANY=true)"
    exit 3
  fi
}
start_run() {  # VERSION -> EID
  EID=$(api POST /api/workflow/start "$(jq -n --arg s "$SID" --arg v "$1" '{sessionId:$s,workflowId:"release",payload:{version:$v}}')" | jq -r .data.executionId)
}
wait_done() {  # [SECONDS] -> STATUS, LOGS
  local i
  for ((i = 0; i < ${1:-90}; i++)); do
    STATUS=$(api GET "/api/executions/$EID" 2>/dev/null | jq -r .data.status || true)
    [[ $STATUS =~ $TERMINAL_STATUSES ]] && break
    sleep 1
  done
  LOGS=$(curl -s -m 15 ${YUKTA_API_KEY:+-H "X-API-Key: $YUKTA_API_KEY"} "$YUKTA_URL/api/sessions/$SID/executions/$EID/logs" | sed -n 's/^data://p' || true)
}
wait_approval() {  # NODE
  local i
  for ((i = 0; i < 40; i++)); do
    api GET "/api/sessions/$SID/approvals" | jq -e --arg e "$EID" --arg n "$1" '.data[] | select(.executionId == $e and .nodeId == $n)' >/dev/null && return 0
    sleep 1
  done
  echo "    FAIL: node $1 never started waiting"; exit 1
}
signal() { api POST "/api/executions/$EID/nodes/$1/signal?name=$2&decision=$3" >/dev/null; }  # NODE NAME DECISION
outcome_of() {  # NODE: the outcome the terminal NODE reported, empty when it was not reached
  api GET "/api/executions/$EID" | jq -r --arg n "$1" '.data.tasks[] | select(.nodeId == $n and .metadata.reached == true) | .metadata.outcome'
}
node_ran() { api GET "/api/executions/$EID" | jq -e --arg n "$1" '.data.tasks[] | select(.nodeId == $n and .status == "SUCCESS")' >/dev/null; }
# expect NAME TERMINAL OUTCOME LIVE_VERSION NOTICE_TEXT
expect() {
  local got live notice
  got=$(outcome_of "$2"); live=$(cat "$STATE/live"); notice=$(tail -n 1 "$STATE/notices.log")
  if [[ $STATUS == SUCCESS && $got == "$3" && $live == "$4" && ( -z "$5" || $notice == *"$5"* ) ]]; then
    echo "    PASS $1: $2 reached ($3), production runs $live"
  else
    echo "    FAIL $1: status=$STATUS, $2 outcome='$got' (want $3), production='$live' (want $4), last notice='$notice' (want '$5')"
    sed 's/^/      /' <<<"$LOGS"; exit 1
  fi
}

check() {  # NAME [!] COMMAND...: fail the example when the command's result is not what is expected
  local name=$1 neg=0; shift
  [[ $1 == "!" ]] && { neg=1; shift; }
  if "$@"; then (( neg == 0 )) || { echo "    FAIL $name: expected false: $*"; exit 1; }
  else (( neg == 1 )) || { echo "    FAIL $name: $*"; exit 1; }; fi
}
# --------------------------------------------------------------------------------------------

require_process
apply_session session.json

echo "--> happy path: build, test, staging, approve, deploy, smoke check"
start_run v1.5.0; wait_approval approve
check staging-before-approval node_ran staging
[[ $(cat "$STATE/live") == v1.4.0 ]] || { echo "    FAIL: production changed before approval"; exit 1; }
signal approve approve-release approve; wait_done
expect released released SUCCESS v1.5.0 "RELEASE LIVE"

echo "--> smoke check fails after deploy: automatic rollback to v1.5.0"
start_run v1.6.0-smoke-fails; wait_approval approve
signal approve approve-release approve; wait_done
check rollback-ran node_ran rollback
expect rolled-back not-released FAILED v1.5.0 "ROLLED BACK"

echo "--> build fails: blocked before staging, no approval is asked"
start_run v1.7.0-build-fails; wait_done
check no-approval ! node_ran approve
check no-staging ! node_ran staging
expect build-fails not-released FAILED v1.5.0 "RELEASE BLOCKED"

echo "--> tests fail: blocked before staging"
start_run v1.7.1-test-fails; wait_done
check no-approval ! node_ran approve
expect test-fails not-released FAILED v1.5.0 "RELEASE BLOCKED"

echo "--> approver rejects: nothing is deployed"
start_run v1.8.0; wait_approval approve
signal approve approve-release reject; wait_done
check no-deploy ! node_ran deploy
expect rejected rejected FAILED v1.5.0 ""

echo "--> nobody approves in time (window shortened to 2 s for the test)"
jq '.workflows.release.nodes |= map(if .nodeId == "approve" then .config.timeoutSeconds = 2 else . end)' session.json >"$STATE/fast.json"
api POST /api/sessions "$(cat "$STATE/fast.json")" >/dev/null
start_run v1.9.0; wait_done
check no-deploy ! node_ran deploy
expect expired needs-owner ESCALATE v1.5.0 "APPROVAL EXPIRED"
api POST /api/sessions "$(cat session.json)" >/dev/null
echo "PASS 03-release-approval"
