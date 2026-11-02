#!/usr/bin/env bash
# Use case: triage dependency bump requests with fixed rules. Patch/minor bumps without a CVE are
# merged at once, major or CVE-flagged ones wait in a review queue for a maintainer, and the
# notification never carries registry tokens or internal fields. This script plays the maintainer: it waits for the queue item, then signals.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
for p in VALIDATE WAIT_FOR_SIGNAL CONTENT_FILTER FILE_WRITE OUTCOME_TERMINAL; do require_plugin "$p"; done

SID=$(jq -r .sessionId session.json)
OUT=/tmp/yukta-example-dependency-updates
rm -rf "$OUT"; mkdir -p "$OUT"; : >"$OUT/changelog.log"; : >"$OUT/outbox.log"

# --- helpers (local to this example) -------------------------------------------------------
start_run() {  # PAYLOAD_FILE -> EID
  EID=$(api POST /api/workflow/start "$(jq -n --arg s "$SID" --slurpfile p "$1" '{sessionId:$s,workflowId:"updates",payload:$p[0]}')" | jq -r .data.executionId)
}
wait_done() {  # [SECONDS] -> STATUS, LOGS
  local i
  for ((i = 0; i < ${1:-60}; i++)); do
    STATUS=$(api GET "/api/executions/$EID" 2>/dev/null | jq -r .data.status || true)
    [[ $STATUS =~ $TERMINAL_STATUSES ]] && break
    sleep 1
  done
  LOGS=$(curl -s -m 15 ${YUKTA_API_KEY:+-H "X-API-Key: $YUKTA_API_KEY"} "$YUKTA_URL/api/sessions/$SID/executions/$EID/logs" | sed -n 's/^data://p' || true)
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

echo "--> patch bump: merged automatically, no human"
start_run patch-bump.json; wait_done
expect patch-bump "decision=MERGE_APPROVED" "decidedBy=rules"
[[ $(lines changelog.log) == 1 ]] || { echo "    FAIL patch-bump: expected 1 changelog line"; exit 1; }

echo "--> invalid request: set aside, nothing merged"
start_run invalid.json; wait_done
expect invalid "decision=NEEDS_INFO"
[[ $(lines changelog.log) == 1 ]] || { echo "    FAIL invalid: a merge was recorded"; exit 1; }

echo "--> major bump with CVE (score 95): maintainer approves"
start_run major-bump.json; wait_approval review-queue
signal review-queue review approve; wait_done
expect maintainer-approve "decidedBy=maintainer" "decision=MERGE_APPROVED"
[[ $(lines changelog.log) == 2 ]] || { echo "    FAIL maintainer-approve: expected 2 changelog lines"; exit 1; }

echo "--> major bump: maintainer denies (decision other than reject, flow goes on)"
start_run major-bump.json; wait_approval review-queue
signal review-queue review deny; wait_done
expect maintainer-deny "decision=MERGE_DENIED"
[[ $(lines changelog.log) == 2 ]] || { echo "    FAIL maintainer-deny: a merge was recorded"; exit 1; }

echo "--> major bump: maintainer rejects (hands it to the security team, nothing sent)"
before=$(lines outbox.log)
start_run major-bump.json; wait_approval review-queue
signal review-queue review reject; wait_done
expect reject
node_reached security-escalation || { echo "    FAIL reject: security-escalation not reached"; exit 1; }
[[ $(lines changelog.log) == 2 && $(lines outbox.log) == "$before" ]] || { echo "    FAIL reject: merge or message sent"; exit 1; }

echo "--> major bump: nobody reviews in time (wait shortened to 2 s for the test)"
jq '.workflows.updates.nodes |= map(if .nodeId == "review-queue" then .config.timeoutSeconds = 2 else . end)' session.json >"$OUT/fast.json"
api POST /api/sessions "$(cat "$OUT/fast.json")" >/dev/null
start_run major-bump.json; wait_done
expect timeout "decision=STILL_IN_REVIEW"
[[ $(lines changelog.log) == 2 ]] || { echo "    FAIL timeout: a merge was recorded"; exit 1; }
api POST /api/sessions "$(cat session.json)" >/dev/null

echo "--> notifications carry no internal fields"
for want in "BUMP-2001 | MERGE_APPROVED" "BUMP-2002 | MERGE_APPROVED" "BUMP-2002 | MERGE_DENIED" "STILL_IN_REVIEW" "NEEDS_INFO"; do
  grep -qF -- "$want" "$OUT/outbox.log" || { echo "    FAIL outbox lacks: $want"; exit 1; }
done
if grep -qE 'npm_|SECRET|9f3a7c|riskScore|\*\*\*\*' "$OUT/outbox.log"; then echo "    FAIL: internal data in outbox"; exit 1; fi
grep -qF 'token=****' "$OUT/changelog.log" || { echo "    FAIL: changelog line missing"; exit 1; }
echo "    PASS outbox: $(lines outbox.log) messages, changelog: $(lines changelog.log) merges"
echo "PASS 05-dependency-updates"
