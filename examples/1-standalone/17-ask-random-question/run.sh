#!/usr/bin/env bash
# Use case: ask yes or no; on yes ask a random question and print question and answer; on no stop.
# This script plays the person: it answers both requests, once with "yes" and once with "no".
# Needs a server that allows `echo` and `shuf` (yukta.process.allowed-commands=echo,shuf).
set -euo pipefail
source "$(dirname "$0")/../../lib/common.sh"
cd "$(dirname "$0")"
require_server
for p in ASK_INPUT BRANCH MAPPER PROCESS_EXECUTOR; do require_plugin "$p"; done
SID=$(jq -r .sessionId session.json)
apply_session session.json

answer() {  # EID NODE JSON-BODY-FIELDS: wait for the open request of NODE and answer it
  local row i
  for ((i = 0; i < 40; i++)); do
    row=$(api GET "/api/sessions/$SID/inputs?state=OPEN" | jq -c --arg e "$1" --arg n "$2" '[.data[] | select(.executionId == $e and .nodeId == $n)][0] // empty')
    [[ -n $row ]] && break; sleep 1
  done
  [[ -n $row ]] || { echo "    FAIL: node $2 never asked"; exit 1; }
  echo "    $2 asks: $(jq -r .prompt <<<"$row")"
  api POST "/api/executions/$1/inputs/$(jq -r .id <<<"$row")/answer" "$(jq -c --argjson v "$(jq .version <<<"$row")" '. + {expectedVersion: $v}' <<<"$3")" >/dev/null
}
finish() {  # EID -> STATUS, LOGS
  local i
  for ((i = 0; i < 30; i++)); do
    STATUS=$(api GET "/api/executions/$1" 2>/dev/null | jq -r .data.status || true)
    [[ $STATUS =~ $TERMINAL_STATUSES ]] && break; sleep 1
  done
  LOGS=$(curl -s -m 15 ${YUKTA_API_KEY:+-H "X-API-Key: $YUKTA_API_KEY"} "$YUKTA_URL/api/sessions/$SID/executions/$1/logs" | sed -n 's/^data://p' || true)
}
start() { api POST /api/workflow/start "$(jq -n --arg s "$SID" '{sessionId:$s,workflowId:"ask"}')" | jq -r .data.executionId; }

echo "--> yes: a random question is asked"
EID=$(start)
answer "$EID" want-question '{"decision":"yes"}'
answer "$EID" ask-question '{"decision":"answer","text":"Ramen, twice."}'
finish "$EID"
grep -qF "Answer: Ramen, twice." <<<"$LOGS" && grep -qF "Question: " <<<"$LOGS" && [[ $STATUS == SUCCESS ]] \
  || { echo "    FAIL yes: status=$STATUS"; echo "$LOGS"; exit 1; }
grep -F "Question: " <<<"$LOGS" | sed 's/^/      /'

echo "--> no: the run ends without a question"
EID=$(start)
answer "$EID" want-question '{"decision":"no"}'
finish "$EID"
[[ $STATUS == SUCCESS ]] && ! grep -qF "Question: " <<<"$LOGS" || { echo "    FAIL no: status=$STATUS"; exit 1; }
echo "    PASS: yes asked and printed, no stopped"
