#!/usr/bin/env bash
# Use case: crash report triage. Drops repeats of a known fingerprint, auto-links known issues to
# their ticket, escalates new, regression and critical crashes to the owning team, records every outcome.
# Needs no LLM, no internet and no external service. Plugins used are always built in.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
for p in VALIDATE KV_STORE BRANCH MAPPER SUB_WORKFLOW RECIPIENT_LIST FILE_WRITE; do require_plugin "$p"; done
apply_session session.json

LOG=/tmp/yukta-examples/crash-report-triage/outcomes.log   # same folder as baseDir in session.json
rm -f "$LOG"

# The repeat check keeps its counters for the life of the server, so each run uses
# its own fingerprints. RUN is added to the top frame of every sample report.
TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT
RUN="run$$-$RANDOM"
report() { # report NAME -> path of a temp payload with a run-specific top frame
  local f; f=$(mktemp "$TMPD/report.XXXX")
  jq --arg r "$RUN" 'if .frame then .frame = (.frame + "#" + $r) else . end' "reports/$1.json" > "$f"
  echo "$f"
}
send() { run_workflow . intake SUCCESS "$2" "$(report "$1")"; }
# The handler runs as a sub-workflow; its node outputs appear in the intake logs.
send timeout                 "outcome=auto_linked"
send null-pointer            "BUG-431"
send null-pointer-untracked  "slack #triage: report R-102"
send oom                     "slack #sre-oncall: report R-103"
send regression              "email platform-oncall@example.com: report R-104"
send other                   "slack #triage: report R-105"
send oom                     "DUPLICATE seen=2"        # the same fingerprint again
send invalid                 "REJECTED invalid report"

want() { grep -qF -- "$1" "$LOG" && echo "    PASS log has: $1" || { echo "    FAIL log lacks: $1"; echo "--- $LOG"; cat "$LOG"; exit 1; }; }
echo "--> outcome log $LOG"
want "report=R-100 category=timeout outcome=auto_linked queue=none"
want "report=R-101 category=npe outcome=auto_linked queue=none"
want "report=R-102 category=npe outcome=escalated queue=triage"
want "report=R-103 category=critical outcome=escalated queue=sre-oncall"
want "report=R-104 category=regression outcome=escalated queue=platform-oncall"
want "report=R-105 category=other outcome=escalated queue=triage"
want "DUPLICATE seen=2"
want "REJECTED invalid report"
[[ $(wc -l < "$LOG") -eq 8 ]] && echo "    PASS exactly 8 outcomes recorded (one per report)" || { echo "    FAIL expected 8 lines"; cat "$LOG"; exit 1; }
echo "PASS 06-crash-report-triage"
