#!/usr/bin/env bash
# Use case: several agents (researcher, writer, independent verifier) run as separate workflows under
# one coordinator, with a bounded retry loop and a hard stop. Also pauses and resumes a live run.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
SID=$(jq -r .sessionId session.json)
FAILED=0
N=0

# start_run PAYLOAD_JSON -> prints the execution id (the run id must be new for every run: it keys the KV state)
start_run() {
  api POST /api/workflow/start "$(jq -n --arg s "$SID" --argjson p "$1" '{sessionId: $s, workflowId: "coordinator", payload: $p}')" | jq -r .data.executionId
}

# finish EXECUTION: wait for the run to end; sets STATUS and LOGS
finish() {
  local i
  for ((i = 0; i < 90; i++)); do
    STATUS=$(api GET "/api/executions/$1" 2>/dev/null | jq -r .data.status || true)
    [[ $STATUS =~ $TERMINAL_STATUSES ]] && break
    sleep 1
  done
  LOGS=$(curl -s -m 15 ${YUKTA_API_KEY:+-H "X-API-Key: $YUKTA_API_KEY"} "$YUKTA_URL/api/sessions/$SID/executions/$1/logs" | sed -n 's/^data://p' || true)
}

expect() {
  local label=$1; shift
  if "$@"; then echo "    ok: $label"; else echo "    FAIL: $label"; FAILED=1; fi
}
logs_have() { grep -qE -- "$1" <<<"$LOGS"; }
logs_lack() { ! grep -qE -- "$1" <<<"$LOGS"; }

# scenario NAME PAYLOAD_JSON: starts the coordinator and sets RUN
scenario() {
  N=$((N + 1))
  echo "--> $1"
  local p; p=$(jq -nc "$2")
  echo "    curl -X POST $YUKTA_URL/api/workflow/start -H 'Content-Type: application/json' -d '$(jq -nc --arg s "$SID" --argjson p "$p" '{sessionId: $s, workflowId: "coordinator", payload: $p}')'"
  RUN=$(start_run "$p")
}
RID="run-$(date +%s)-$$"

apply_session session.json

scenario "writer fixes the draft on attempt 2" "{runId: \"$RID-a\", topic: \"Yukta\", fixAt: 2}"
finish "$RUN"
expect "run succeeded" [ "$STATUS" = SUCCESS ]
expect "verifier rejected attempt 1 with feedback" logs_have "add the missing facts: \[nodes are plugins, runs can be paused\]; remove unsupported claims: \[always, perfectly\]"
expect "writer received that feedback in attempt 2" logs_have "addressed=add the missing facts"
expect "accepted after 2 attempts" logs_have "result=ACCEPTED.*attempts=2|attempts=2.*result=ACCEPTED"
expect "no third attempt" logs_lack "Iteration 3"

scenario "writer needs all 3 attempts" "{runId: \"$RID-b\", topic: \"Yukta\", fixAt: 3}"
finish "$RUN"
expect "run succeeded" [ "$STATUS" = SUCCESS ]
expect "accepted after 3 attempts" logs_have "result=ACCEPTED.*attempts=3|attempts=3.*result=ACCEPTED"

scenario "writer never improves: hard stop after the cap" "{runId: \"$RID-c\", topic: \"Yukta\", fixAt: 99}"
finish "$RUN"
expect "run ended normally with outcome FAILED" [ "$STATUS" = SUCCESS ]
expect "hard stop names the cap and the counter from KV" logs_have "HARD STOP.*not verified after 3 attempts \(cap 3\)|not verified after 3 attempts \(cap 3\).*HARD STOP"
expect "no fourth attempt" logs_lack "Iteration 4"
expect "nothing was accepted" logs_lack "result=ACCEPTED"

scenario "request without a topic is refused" "{runId: \"$RID-d\"}"
finish "$RUN"
expect "refused" logs_have "result=REFUSED"
expect "no agent ran" logs_lack "SUB_WORKFLOW"

scenario "a person pauses the whole run tree, then resumes it" "{runId: \"$RID-e\", topic: \"Yukta\", fixAt: 2}"
sleep 1
echo "    pause:  curl -X POST $YUKTA_URL/api/executions/$RUN/pause?tree=true"
api POST "/api/executions/$RUN/pause?tree=true" >/dev/null
sleep 1
CS=$(api GET "/api/executions/$RUN" | jq -r '.data.controlState')
expect "control state is PAUSED" [ "$CS" = PAUSED ]
sleep 3
expect "still running after 3 more seconds" [ "$(api GET "/api/executions/$RUN" | jq -r .data.status)" != SUCCESS ]
echo "    resume: curl -X POST $YUKTA_URL/api/executions/$RUN/resume?tree=true"
api POST "/api/executions/$RUN/resume?tree=true" >/dev/null
finish "$RUN"
expect "run succeeded after resume" [ "$STATUS" = SUCCESS ]
expect "accepted" logs_have "result=ACCEPTED"

if (( FAILED )); then echo "FAIL 13-multi-agent"; exit 1; fi
echo "PASS 13-multi-agent: $N paths"
