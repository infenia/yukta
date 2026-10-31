#!/usr/bin/env bash
# Use case: nightly event-log load with row checks, a quarantine file and a bad-row limit.
# The cron trigger is shown in session.json; to test quickly this script
#   1. starts the `pipeline` workflow by REST (the part that does the work), and
#   2. applies a copy of the session whose schedule is "every 2 s, once" and runs `nightly`.
# Needs the file-io plugins to be allowed to write under /tmp/yukta-examples. No database.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
for p in SCHEDULE_TRIGGER FILE_READ FILE_WRITE SPLITTER VALIDATE KV_STORE AGGREGATE SUB_WORKFLOW; do require_plugin "$p"; done

SANDBOX=/tmp/yukta-examples/nightly            # same folder as baseDir in session.json
SID=$(jq -r .sessionId session.json)
TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT
RUN="r$$x$RANDOM"
rm -rf "$SANDBOX/out" "$SANDBOX/inbox"; mkdir -p "$SANDBOX/inbox"

# The repeat check remembers every row it has seen for the life of the server, so each
# input file gets its own id prefix (E1 becomes E1 of this run, and so on).
stage() { sed "s/^E/E$RUN$2-/" "data/$1" > "$SANDBOX/inbox/$3"; }
payload() { jq -n --arg p "$1" '{path: $p}' > "$TMPD/payload.json"; echo "$TMPD/payload.json"; }
check() { # check DESCRIPTION CONDITION...
  local d=$1; shift
  if "$@"; then echo "    PASS $d"; else echo "    FAIL $d"; exit 1; fi
}
has() { grep -qF -- "$2" "$1"; }
lines() { [[ $(wc -l < "$1") -eq $2 ]]; }

apply_session session.json

echo "== 1. good file: 8 data rows = 6 good, 1 bad, 1 repeat; limit not exceeded"
stage events-ok.csv a events-ok.csv
run_workflow . pipeline SUCCESS "rows=8 good=6 bad=1 repeat=1" "$(payload inbox/events-ok.csv)"
check "summary line written"       has "$SANDBOX/out/summary.log" "rows=8 good=6 bad=1 repeat=1"
check "bad event is in quarantine"   has "$SANDBOX/out/quarantine.csv" "teleport"
check "quarantine holds 1 row"     lines "$SANDBOX/out/quarantine.csv" 1
check "good rows loaded in 2 batches of 3" lines "$SANDBOX/out/loaded-batches.txt" 2
check "replayed event not loaded twice" test "$(grep -o "E${RUN}a-2|" "$SANDBOX/out/loaded-batches.txt" | wc -l)" -eq 1

echo "== 2. bad file: 5 of 6 rows bad, run must fail"
stage events-bad.csv b events-bad.csv
run_workflow . pipeline FAILURE "rows=6 good=1 bad=5" "$(payload inbox/events-bad.csv)"
check "summary still written"      has "$SANDBOX/out/summary.log" "rows=6 good=1 bad=5"
check "bad rows quarantined"       has "$SANDBOX/out/quarantine.csv" "E${RUN}b-6"

echo "== 3. missing file: the run fails at read-file"
run_workflow . pipeline FAILURE "" "$(payload inbox/does-not-exist.csv)"

echo "== 4. scheduled workflow (schedule shortened to one tick after 2 s)"
jq '.workflows.nightly.nodes |= map(if .nodeId == "at-2am" then .config = {intervalMs: 2000, maxRuns: 1, timeoutSeconds: 30} else . end)' session.json > "$TMPD/session.json"
apply_session "$TMPD/session.json"
stage events-ok.csv c events.csv
run_workflow "$TMPD" nightly SUCCESS "rows=8 good=6 bad=1 repeat=1"
check "no alert after a good night" test ! -e "$SANDBOX/out/alerts.log"
stage events-bad.csv d events.csv
run_workflow "$TMPD" nightly SUCCESS "ALERT nightly event load failed"
check "alert recorded after a bad night" has "$SANDBOX/out/alerts.log" "ALERT nightly event load failed"

apply_session session.json   # leave the real cron schedule applied
echo "PASS 07-event-data-pipeline"
