#!/usr/bin/env bash
# Use case: screen return/refund requests for fraud with fixed rules. Low risk is refunded at
# once, high risk waits in a review queue for a human, and the customer message never carries
# internal fields. This script plays the reviewer: it waits for the queue item, then signals.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
for p in VALIDATE WAIT_FOR_SIGNAL CONTENT_FILTER FILE_WRITE OUTCOME_TERMINAL; do require_plugin "$p"; done

SID=$(jq -r .sessionId session.json)
OUT=/tmp/yukta-example-returns-fraud
rm -rf "$OUT"; mkdir -p "$OUT"; : >"$OUT/refunds.log"; : >"$OUT/outbox.log"

# --- helpers (local to this example) -------------------------------------------------------
start_run() {  # PAYLOAD_FILE -> EID
  EID=$(api POST /api/workflow/start "$(jq -n --arg s "$SID" --slurpfile p "$1" '{sessionId:$s,workflowId:"returns",payload:$p[0]}')" | jq -r .data.executionId)
}
wait_done() {  # [SECONDS] -> STATUS, LOGS
  local i
  for ((i = 0; i < ${1:-60}; i++)); do
    STATUS=$(api GET "/api/executions/$EID" 2>/dev/null | jq -r .data.status || true)
    [[ $STATUS =~ $TERMINAL_STATUSES ]] && break
    sleep 1
  done
  LOGS=$(curl -s -m 15 "$YUKTA_URL/api/sessions/$SID/executions/$EID/logs" | sed -n 's/^data://p' || true)
}
wait_approval() {  # NODE: poll pending approvals until NODE of this run is waiting
  local i
  for ((i = 0; i < 30; i++)); do
    api GET "/api/sessions/$SID/approvals" | jq -e --arg e "$EID" --arg n "$1" '.data[] | select(.executionId == $e and .nodeId == $n)' >/dev/null && return 0
    sleep 1
  done
  echo "    FAIL: node $1 never started waiting"; exit 1
}
signal() { api POST "/api/executions/$EID/nodes/$1/signal?name=$2&decision=$3" >/dev/null; }  # NODE NAME DECISION
node_reached() { api GET "/api/executions/$EID" | jq -e --arg n "$1" '.data.tasks[] | select(.nodeId == $n and .metadata.reached == true)' >/dev/null; }
expect() {  # NAME TEXT... : status must be SUCCESS and every TEXT must be in the logs
  local name=$1 t ok=1; shift
  [[ $STATUS == SUCCESS ]] || ok=0
  for t in "$@"; do grep -qF -- "$t" <<<"$LOGS" || { ok=0; echo "    missing in logs: $t"; }; done
  if (( ok )); then echo "    PASS $name: status=$STATUS"; else echo "    FAIL $name: status=$STATUS"; sed 's/^/      /' <<<"$LOGS"; exit 1; fi
}
lines() { wc -l <"$OUT/$1"; }
# --------------------------------------------------------------------------------------------

apply_session session.json

echo "--> low risk: refunded automatically, no human"
start_run low.json; wait_done
expect low-risk "decision=REFUND_APPROVED" "decidedBy=rules"
[[ $(lines refunds.log) == 1 ]] || { echo "    FAIL low-risk: expected 1 refund"; exit 1; }

echo "--> invalid request: set aside, no refund"
start_run invalid.json; wait_done
expect invalid "decision=NEEDS_INFO"
[[ $(lines refunds.log) == 1 ]] || { echo "    FAIL invalid: a refund was paid"; exit 1; }

echo "--> high risk (score 115): reviewer approves"
start_run high.json; wait_approval review-queue
signal review-queue review approve; wait_done
expect reviewer-approve "decidedBy=reviewer" "decision=REFUND_APPROVED"
[[ $(lines refunds.log) == 2 ]] || { echo "    FAIL reviewer-approve: expected 2 refunds"; exit 1; }

echo "--> high risk: reviewer denies (decision other than reject, flow goes on)"
start_run high.json; wait_approval review-queue
signal review-queue review deny; wait_done
expect reviewer-deny "decision=REFUND_DENIED"
[[ $(lines refunds.log) == 2 ]] || { echo "    FAIL reviewer-deny: a refund was paid"; exit 1; }

echo "--> high risk: reviewer rejects (hands the case to the fraud team, nothing sent)"
before=$(lines outbox.log)
start_run high.json; wait_approval review-queue
signal review-queue review reject; wait_done
expect reject
node_reached fraud-escalation || { echo "    FAIL reject: fraud-escalation not reached"; exit 1; }
[[ $(lines refunds.log) == 2 && $(lines outbox.log) == "$before" ]] || { echo "    FAIL reject: refund or message sent"; exit 1; }

echo "--> high risk: nobody reviews in time (wait shortened to 2 s for the test)"
jq '.workflows.returns.nodes |= map(if .nodeId == "review-queue" then .config.timeoutSeconds = 2 else . end)' session.json >"$OUT/fast.json"
api POST /api/sessions "$(cat "$OUT/fast.json")" >/dev/null
start_run high.json; wait_done
expect timeout "decision=STILL_IN_REVIEW"
[[ $(lines refunds.log) == 2 ]] || { echo "    FAIL timeout: a refund was paid"; exit 1; }
api POST /api/sessions "$(cat session.json)" >/dev/null

echo "--> customer messages carry no internal fields"
for want in "RET-2001 | REFUND_APPROVED" "RET-2002 | REFUND_APPROVED" "RET-2002 | REFUND_DENIED" "STILL_IN_REVIEW" "NEEDS_INFO"; do
  grep -qF -- "$want" "$OUT/outbox.log" || { echo "    FAIL outbox lacks: $want"; exit 1; }
done
if grep -qE '4242|1111|riskScore|\*\*\*\*' "$OUT/outbox.log"; then echo "    FAIL: internal data in outbox"; exit 1; fi
grep -qF 'card=****4242' "$OUT/refunds.log" || { echo "    FAIL: refund ledger line missing"; exit 1; }
echo "    PASS outbox: $(lines outbox.log) messages, refund ledger: $(lines refunds.log) refunds"
echo "PASS 05-returns-fraud"
