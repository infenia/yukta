#!/usr/bin/env bash
# Use case: a retrained model candidate goes through eval checks, then an ML reviewer approves it,
# asks for a retrain (up to 3 rounds) or rejects it. This script plays the reviewer through the signal API.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
SID=$(jq -r .sessionId session.json)
OUT=/tmp/yukta-examples/model-promotion   # the baseDir used by the FILE_WRITE nodes in session.json
ANSWERED=" "

# start_run WORKFLOW PAYLOAD_FILE -> prints the execution id
start_run() {
  local body
  body=$(jq -n --arg s "$SID" --arg w "$1" --slurpfile p "$2" '{sessionId: $s, workflowId: $w, payload: $p[0]}')
  api POST /api/workflow/start "$body" | jq -r .data.executionId
}

# signal EXECUTION NODE NAME DECISION [DATA]: what the reviewer's button does.
signal() {
  local q="name=$3&decision=$4"
  [[ -n "${5:-}" ]] && q+="&data=$(jq -rn --arg d "$5" '$d|@uri')"
  api POST "/api/executions/$1/nodes/$2/signal?$q" >/dev/null
}

# answer NAME DECISION [DATA]: wait for the next open approval called NAME, then answer it.
answer() {
  local name=$1 row eid node i
  for ((i = 0; i < 60; i++)); do
    row=$(api GET "/api/sessions/$SID/approvals" | jq -c --arg n "$name" '[.data[] | select(.name == $n)][]' | while read -r r; do
      e=$(jq -r .executionId <<<"$r"); [[ "$ANSWERED" == *" $e "* ]] && continue
      # only approvals that belong to this run (a round is a child execution of RUN)
      [[ "$(api GET "/api/executions/$e" | jq -r .data.rootExecutionId)" == "$RUN" ]] && { echo "$r"; break; }
    done)
    [[ -n "$row" ]] && break
    sleep 0.5
  done
  [[ -n "$row" ]] || { echo "    FAIL: no pending approval '$name'"; return 1; }
  eid=$(jq -r .executionId <<<"$row"); node=$(jq -r .nodeId <<<"$row")
  ANSWERED+="$eid "
  echo "    reviewer: $2${3:+ ($3)} on $node of $eid"
  signal "$eid" "$node" "$name" "$2" "${3:-}"
}

# finish EXECUTION: wait for the run to end; sets STATUS and LOGS.
finish() {
  local i
  for ((i = 0; i < 60; i++)); do
    STATUS=$(api GET "/api/executions/$1" 2>/dev/null | jq -r .data.status || true)
    [[ $STATUS =~ $TERMINAL_STATUSES ]] && break
    sleep 1
  done
  LOGS=$(curl -s -m 15 ${YUKTA_API_KEY:+-H "X-API-Key: $YUKTA_API_KEY"} "$YUKTA_URL/api/sessions/$SID/executions/$1/logs" | sed -n 's/^data://p' || true)
}

# expect LABEL CONDITION...: PASS/FAIL line for one assertion
FAILED=0
expect() {
  local label=$1; shift
  if "$@"; then echo "    ok: $label"; else echo "    FAIL: $label"; FAILED=1; fi
}
outbox_has() { grep -qF -- "$1" "$OUT/outbox.log" 2>/dev/null; }
page_has() { grep -qF -- "$2" "$OUT/registry/$1" 2>/dev/null; }
no_page() { [[ -z "$(ls "$OUT/registry" 2>/dev/null)" ]]; }

# scenario NAME PAYLOAD_JSON: starts the promotion with a fresh output folder
scenario() {
  echo "--> $1"
  rm -rf "$OUT"; mkdir -p "$OUT"
  jq -n "$2" > "$OUT/payload.json"
  RUN=$(start_run review "$OUT/payload.json")
  echo "    curl -X POST $YUKTA_URL/api/workflow/start -H 'Content-Type: application/json' -d '$(jq -nc --arg s "$SID" --slurpfile p "$OUT/payload.json" '{sessionId: $s, workflowId: "review", payload: $p[0]}')'"
}

apply_session session.json

scenario "clean candidate, reviewer approves" '{name: "churn-model", version: "v7", accuracy: 0.93, latencyMs: 120, biasGap: 0.03}'
answer promote approve
finish "$RUN"
expect "run succeeded" [ "$STATUS" = SUCCESS ]
expect "model promoted" page_has churn-model-v7.json "accuracy 0.93"
expect "team notified" outbox_has "PROMOTED: churn-model v7"

scenario "low accuracy is caught before the reviewer sees it" '{name: "fraud-model", version: "v3", accuracy: 0.88, latencyMs: 90, biasGap: 0.02}'
answer promote approve
finish "$RUN"
expect "run succeeded" [ "$STATUS" = SUCCESS ]
expect "round 1 failed the automatic check" grep -q "automatic check failed: accuracy below floor" <<<"$LOGS"
expect "promoted model is the round 2 retrain" page_has fraud-model-v3.json "round 2"

scenario "reviewer asks for a retrain, notes reach the next round" '{name: "rank-model", version: "v5", accuracy: 0.92, latencyMs: 150, biasGap: 0.04}'
answer promote retrain "rebalance the training set, check the age group"
answer promote approve
finish "$RUN"
expect "run succeeded" [ "$STATUS" = SUCCESS ]
expect "notes carried into round 2" grep -qE "start-round-2/MAPPER.*notes=rebalance the training set, check the age group" <<<"$LOGS"
expect "round 2 was promoted" page_has rank-model-v5.json "round 2"

scenario "reviewer keeps asking for retrains: stop after 3 rounds" '{name: "vision-model", version: "v2", accuracy: 0.95, latencyMs: 100, biasGap: 0.01}'
answer promote retrain "more data"
answer promote retrain "still drifting"
answer promote retrain "try again"
finish "$RUN"
expect "run ended normally (outcome FAILED, not a crash)" [ "$STATUS" = SUCCESS ]
expect "team told it needs a model owner" outbox_has "not approved after 3 rounds"
expect "nothing promoted" no_page

scenario "reviewer rejects the candidate" '{name: "risky-model", version: "v1", accuracy: 0.94, latencyMs: 110, biasGap: 0.04}'
answer promote reject
finish "$RUN"
expect "team told it was rejected" outbox_has "the reviewer rejected the candidate"
expect "nothing promoted" no_page

scenario "too slow: only the automatic check runs, no reviewer involved" '{name: "slow-model", version: "v9", accuracy: 0.96, latencyMs: 900, biasGap: 0.01}'
finish "$RUN"
expect "latency rule fired" grep -q "latency too high" <<<"$LOGS"
expect "team told it needs a model owner" outbox_has "not approved after 3 rounds"

scenario "invalid candidate is refused up front" '{name: "no-metrics", version: "v0"}'
finish "$RUN"
expect "team told the candidate is invalid" outbox_has "the candidate is invalid"

rm -rf "$OUT"
if (( FAILED )); then echo "FAIL 12-model-promotion"; exit 1; fi
echo "PASS 12-model-promotion: 7 paths"
