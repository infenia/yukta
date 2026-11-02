#!/usr/bin/env bash
# Use case: triage a batch of security scanner findings. Each one is validated, stripped of leaked secret
# values, scored with fixed weights (severity x reachability x exposure), and (unless it is low) decided by
# an engineer. This script plays the engineer through the signal API.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
SID=$(jq -r .sessionId session.json)
OUT=/tmp/yukta-examples/security-findings   # the baseDir used by the FILE_WRITE nodes in session.json
FAILED=0
ANSWERED=" "

start_run() {
  api POST /api/workflow/start "$(jq -n --arg s "$SID" --slurpfile p "$1" '{sessionId: $s, workflowId: "batch", payload: $p[0]}')" | jq -r .data.executionId
}

# answer DECISION: wait for the next open "decision" approval of this batch and answer it.
# Findings are triaged one at a time, so approvals arrive in batch order.
answer() {
  local row e i
  for ((i = 0; i < 80; i++)); do
    row=""
    while read -r r; do
      [[ -n "$r" ]] || continue
      e=$(jq -r .executionId <<<"$r")
      [[ "$ANSWERED" == *" $e "* ]] && continue
      [[ "$(api GET "/api/executions/$e" | jq -r .data.rootExecutionId)" == "$RUN" ]] && { row=$r; break; }
    done < <(api GET "/api/sessions/$SID/approvals" | jq -c '.data[] | select(.name == "decision")')
    [[ -n "$row" ]] && break
    sleep 0.5
  done
  [[ -n "$row" ]] || { echo "    FAIL: no pending approval"; return 1; }
  e=$(jq -r .executionId <<<"$row"); ANSWERED+="$e "
  CHILD_RUNS+=("$e")
  echo "    engineer: $1 on $(jq -r .nodeId <<<"$row") of $e"
  api POST "/api/executions/$e/nodes/$(jq -r .nodeId <<<"$row")/signal?name=decision&decision=$1" >/dev/null
}

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
file_has() { grep -qF -- "$2" "$OUT/$1" 2>/dev/null; }
file_lacks() { ! grep -qF -- "$2" "$OUT/$1" 2>/dev/null; }

apply_session session.json
rm -rf "$OUT"; mkdir -p "$OUT"
CHILD_RUNS=()

echo "--> batch of 6 findings"
echo "    curl -X POST $YUKTA_URL/api/workflow/start -H 'Content-Type: application/json' -d '$(jq -nc --arg s "$SID" --slurpfile p findings.json '{sessionId: $s, workflowId: "batch", payload: $p[0]}')'"
RUN=$(start_run findings.json)
# F-101 (100) and F-106 (70) strong, F-102 (42) and F-103 (40) in the middle; F-104 (2) is auto-suppressed, F-105 is invalid.
answer fix
answer accept-risk
answer suppress
answer fix
finish "$RUN"
expect "run succeeded" [ "$STATUS" = SUCCESS ]
expect "F-101 to fix" file_has fixes.txt "FIX F-101"
expect "F-106 to fix" file_has fixes.txt "FIX F-106"
expect "F-102 risk accepted" file_has accepted-risks.txt "ACCEPT F-102"
expect "F-103 suppressed by the engineer" file_has suppressions.txt "SUPPRESS F-103"
expect "F-104 suppressed without an engineer" file_has suppressions.txt "SUPPRESS F-104"
expect "F-105 invalid, in no outcome file" bash -c "! grep -rq F-105 '$OUT'"
expect "summary counts every finding" file_has summary.txt "SUMMARY: 6 findings, fix=2 accept=1 suppress=2 invalid=1"

# The engineer's nodes must not have seen secret values or who committed them.
SEEN=$(for c in "${CHILD_RUNS[@]}"; do curl -s -m 15 ${YUKTA_API_KEY:+-H "X-API-Key: $YUKTA_API_KEY"} "$YUKTA_URL/api/sessions/$SID/executions/$c/logs" | sed -n 's/^data://p' | grep -E '\[(score|band|engineer|decide)/'; done)
expect "engineer step saw scores" grep -q "score=100" <<<"$SEEN"
expect "no secret, key block or author email after the filter" bash -c "! grep -qE 'AKIA|ghp_|BEGIN RSA|matchedSecret|rawLine|committedBy|@example.com' <<<\"\$SEEN\""

echo "--> batch without findings is refused"
echo '{"scan": "nightly"}' > "$OUT/bad.json"
RUN=$(start_run "$OUT/bad.json")
finish "$RUN"
expect "refused" file_has summary.txt "BATCH REFUSED"

rm -rf "$OUT"
if (( FAILED )); then echo "FAIL 14-security-findings"; exit 1; fi
echo "PASS 14-security-findings: batch of 6 (fix, accept, suppress, auto-suppress, invalid) and a refused batch"
