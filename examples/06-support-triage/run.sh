#!/usr/bin/env bash
# Use case: support ticket triage. Drops repeated tickets, auto-answers routine ones,
# queues billing disputes and angry customers for a person, and records every outcome.
# Needs no LLM, no internet and no external service. Plugins used are always built in.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
for p in VALIDATE KV_STORE BRANCH MAPPER SUB_WORKFLOW RECIPIENT_LIST FILE_WRITE; do require_plugin "$p"; done
apply_session session.json

LOG=/tmp/yukta-examples/support-triage/outcomes.log   # same folder as baseDir in session.json
rm -f "$LOG"

# The repeat check keeps its counters for the life of the server, so each run uses
# its own customer names. RUN is added to the customer of every sample ticket.
TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT
RUN="run$$-$RANDOM"
ticket() { # ticket NAME -> path of a temp payload with a run-specific customer
  local f; f=$(mktemp "$TMPD/ticket.XXXX")
  jq --arg r "$RUN" 'if .customer then .customer = (.customer | sub("@"; "+" + $r + "@")) else . end' "tickets/$1.json" > "$f"
  echo "$f"
}
send() { run_workflow . intake SUCCESS "$2" "$(ticket "$1")"; }
# The handler runs as a sub-workflow; its node outputs appear in the intake logs.
send password     "outcome=auto_replied"
send order        "A-1042"
send order-no-id  "slack #general: ticket T-102"
send billing      "slack #billing-disputes: ticket T-103"
send angry        "email senior-support@example.com: ticket T-104"
send other        "slack #general: ticket T-105"
send billing      "DUPLICATE seen=2"        # the same ticket again
send invalid      "REJECTED invalid ticket"

want() { grep -qF -- "$1" "$LOG" && echo "    PASS log has: $1" || { echo "    FAIL log lacks: $1"; echo "--- $LOG"; cat "$LOG"; exit 1; }; }
echo "--> outcome log $LOG"
want "ticket=T-100 category=login outcome=auto_replied queue=none"
want "ticket=T-101 category=order outcome=auto_replied queue=none"
want "ticket=T-102 category=order outcome=escalated queue=general"
want "ticket=T-103 category=billing outcome=escalated queue=billing-disputes"
want "ticket=T-104 category=angry outcome=escalated queue=senior-support"
want "ticket=T-105 category=other outcome=escalated queue=general"
want "DUPLICATE seen=2"
want "REJECTED invalid ticket"
[[ $(wc -l < "$LOG") -eq 8 ]] && echo "    PASS exactly 8 outcomes recorded (one per ticket)" || { echo "    FAIL expected 8 lines"; cat "$LOG"; exit 1; }
echo "PASS 06-support-triage"
