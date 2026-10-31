#!/usr/bin/env bash
# Use case: score a loan application with fixed rules, auto-decide clear cases, ask a human for
# borderline ones, and make sure no sensitive field reaches the notification.
# This script plays the underwriter: it waits for the pending approval, then sends the signal.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
for p in VALIDATE WAIT_FOR_SIGNAL CONTENT_FILTER FILE_WRITE; do require_plugin "$p"; done

SID=$(jq -r .sessionId session.json)
OUT=/tmp/yukta-example-loan-approval
rm -rf "$OUT"; mkdir -p "$OUT"

# --- helpers (local to this example) -------------------------------------------------------
# start_run PAYLOAD_FILE -> EID
start_run() {
  EID=$(api POST /api/workflow/start "$(jq -n --arg s "$SID" --slurpfile p "$1" '{sessionId:$s,workflowId:"loan",payload:$p[0]}')" | jq -r .data.executionId)
}
# wait_done [SECONDS] -> STATUS, LOGS
wait_done() {
  local i
  for ((i = 0; i < ${1:-60}; i++)); do
    STATUS=$(api GET "/api/executions/$EID" 2>/dev/null | jq -r .data.status || true)
    [[ $STATUS =~ $TERMINAL_STATUSES ]] && break
    sleep 1
  done
  LOGS=$(curl -s -m 15 "$YUKTA_URL/api/sessions/$SID/executions/$EID/logs" | sed -n 's/^data://p' || true)
}
# wait_approval NODE: poll the pending approvals until NODE of this run is waiting
wait_approval() {
  local i
  for ((i = 0; i < 30; i++)); do
    api GET "/api/sessions/$SID/approvals" | jq -e --arg e "$EID" --arg n "$1" '.data[] | select(.executionId == $e and .nodeId == $n)' >/dev/null && return 0
    sleep 1
  done
  echo "    FAIL: node $1 never started waiting"; exit 1
}
# signal NODE NAME DECISION
signal() { api POST "/api/executions/$EID/nodes/$1/signal?name=$2&decision=$3" >/dev/null; }
# expect NAME TEXT...  (status must be SUCCESS and every TEXT must be in the logs)
expect() {
  local name=$1 t ok=1; shift
  [[ $STATUS == SUCCESS ]] || ok=0
  for t in "$@"; do grep -qF -- "$t" <<<"$LOGS" || { ok=0; echo "    missing in logs: $t"; }; done
  if (( ok )); then echo "    PASS $name: status=$STATUS"; else echo "    FAIL $name: status=$STATUS"; sed 's/^/      /' <<<"$LOGS"; exit 1; fi
}
# node_reached NODE: true when the terminal NODE received a message in this run
node_reached() { api GET "/api/executions/$EID" | jq -e --arg n "$1" '.data.tasks[] | select(.nodeId == $n and .metadata.reached == true)' >/dev/null; }
# --------------------------------------------------------------------------------------------

apply_session session.json

echo "--> clear approve (score 100): no human involved"
start_run approve.json; wait_done
expect auto-approve "decision=APPROVED, decidedBy=rules" "Application APP-1001: APPROVED by rules"

echo "--> clear decline (score 0)"
start_run decline.json; wait_done
expect auto-decline "decision=DECLINED, decidedBy=rules" "Application APP-1002: DECLINED by rules"

echo "--> invalid input is set aside before scoring"
start_run invalid.json; wait_done
expect invalid "decision=NEEDS_INFO" "Application APP-1004: NEEDS_INFO by validator"
grep -qF "score=" <<<"$LOGS" && { echo "    FAIL invalid: it was scored"; exit 1; }

echo "--> borderline (score 60): underwriter approves"
start_run review.json; wait_approval underwriter
signal underwriter underwriter approve; wait_done
expect human-approve "Application APP-1003: APPROVED by underwriter"

echo "--> borderline: underwriter declines (a decision other than reject, so the flow goes on)"
start_run review.json; wait_approval underwriter
signal underwriter underwriter decline; wait_done
expect human-decline "Application APP-1003: DECLINED by underwriter"

echo "--> borderline: underwriter rejects (pulls the case: run stops, nothing is sent)"
before=$(wc -l <"$OUT/outbox.log")
start_run review.json; wait_approval underwriter
signal underwriter underwriter reject; wait_done
expect reject
node_reached rejected-stop || { echo "    FAIL reject: rejected-stop not reached"; exit 1; }
[[ $(wc -l <"$OUT/outbox.log") == "$before" ]] || { echo "    FAIL reject: a notification was sent"; exit 1; }

echo "--> borderline: nobody answers (wait shortened to 2 s for the test)"
jq '.workflows.loan.nodes |= map(if .nodeId == "underwriter" then .config.timeoutSeconds = 2 else . end)' session.json >"$OUT/fast.json"
api POST /api/sessions "$(cat "$OUT/fast.json")" >/dev/null
start_run review.json; wait_done
expect timeout "Application APP-1003: ESCALATED by timeout"
api POST /api/sessions "$(cat session.json)" >/dev/null

echo "--> the notification outbox holds decisions and no sensitive fields"
for want in "APP-1001: APPROVED" "APP-1002: DECLINED" "APP-1003: APPROVED by underwriter" "APP-1003: DECLINED by underwriter" "ESCALATED"; do
  grep -qF -- "$want" "$OUT/outbox.log" || { echo "    FAIL outbox lacks: $want"; exit 1; }
done
if grep -qE '123-45-6789|987-65-4321|555-12-3456|100000|creditScore' "$OUT/outbox.log"; then echo "    FAIL: sensitive data in outbox"; exit 1; fi
echo "    PASS outbox: $(wc -l <"$OUT/outbox.log") notifications, nothing sensitive"
echo "PASS 04-loan-approval"
