#!/usr/bin/env bash
# Use case: alert triage. Low severity is logged. High severity gets a bounded
# restart-and-check loop; if the service is still down, the on-call is paged, an incident
# is opened, and the run waits for the on-call to acknowledge (the script plays the on-call).
#
# The alert is started over REST (API_TRIGGER). For a signed webhook, see the README.
# Needs: FILE_WRITE allowed under /tmp/yukta-examples, python3 (a tiny local "pager"
# that receives the NOTIFY_TERMINAL POST on 127.0.0.1:9877, the url set in session.json).
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
for p in LOOP_PREDICATE SUB_WORKFLOW NOTIFY_TERMINAL WAIT_FOR_SIGNAL RECIPIENT_LIST KV_STORE FILE_WRITE; do require_plugin "$p"; done
command -v python3 >/dev/null || { echo "SKIP: python3 is needed for the local pager"; exit 3; }

SANDBOX=/tmp/yukta-examples/alert-triage       # same folder as baseDir in session.json
PORT=9877                                      # same port as the NOTIFY_TERMINAL url
SID=$(jq -r .sessionId session.json)
TMPD=$(mktemp -d)
RUN="r$$x$RANDOM"
PAGES="$TMPD/pages.log"; : > "$PAGES"
rm -rf "$SANDBOX"; mkdir -p "$SANDBOX"
trap 'kill "${PAGER_PID:-}" 2>/dev/null || true; rm -rf "$TMPD"' EXIT

# --- local helpers ----------------------------------------------------------------
cat > "$TMPD/pager.py" <<PY
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        open(sys.argv[1], "ab").write(body + b"\n")
        self.send_response(200); self.end_headers()
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", $PORT), H).serve_forever()
PY
python3 "$TMPD/pager.py" "$PAGES" & PAGER_PID=$!
for _ in $(seq 20); do (echo > "/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && break; sleep 0.25; done
(echo > "/dev/tcp/127.0.0.1/$PORT") 2>/dev/null || { echo "FAIL: pager did not start on port $PORT (in use?)"; exit 1; }

start_alert() { # start_alert ID SERVICE SEVERITY -> execution id
  api POST /api/workflow/start "$(jq -nc --arg s "$SID" --arg i "$1" --arg v "$2" --arg e "$3" \
    '{sessionId:$s, workflowId:"alert", payload:{alertId:$i, service:$v, severity:$e, summary:"health check failing"}}')" | jq -r .data.executionId
}
wait_status() { # wait_status EID WANT
  local st=""
  for _ in $(seq "${WAIT_SECONDS:-60}"); do
    st=$(api GET "/api/executions/$1" 2>/dev/null | jq -r .data.status || true)
    [[ $st =~ $TERMINAL_STATUSES ]] && break; sleep 1
  done
  [[ $st == "$2" ]] && echo "    PASS execution $st" || { echo "    FAIL execution status $st (want $2)"; exit 1; }
}
await_approval() { # await_approval EID NODE: the pending list names the node that waits
  for _ in $(seq 60); do
    api GET "/api/sessions/$SID/approvals" | jq -e --arg e "$1" --arg n "$2" '.data[] | select(.executionId == $e and .nodeId == $n)' >/dev/null && { echo "    approval pending at $2"; return; }
    sleep 0.5
  done
  echo "    FAIL no pending approval at $2"; exit 1
}
decide() { # decide EID NODE SIGNAL DECISION
  api POST "/api/executions/$1/nodes/$2/signal?name=$3&decision=$4" >/dev/null && echo "    on-call signals $3=$4"
}
check() { local d=$1; shift; if "$@"; then echo "    PASS $d"; else echo "    FAIL $d"; for f in "$SANDBOX"/*.log "$PAGES"; do echo "--- $f"; cat "$f" 2>/dev/null; done; exit 1; fi; }
has() { grep -qF -- "$2" "$1"; }
hasnt() { ! grep -qF -- "$2" "$1" 2>/dev/null; }

apply_session session.json

echo "== 1. low and unknown severity are only logged"
e=$(start_alert "L1-$RUN" web-cache low);       wait_status "$e" SUCCESS
e=$(start_alert "U1-$RUN" web-cache whatever);  wait_status "$e" SUCCESS
check "low logged"     has "$SANDBOX/alerts.log" "LOGGED alert=L1-$RUN service=web-cache severity=low"
check "unknown logged" has "$SANDBOX/alerts.log" "LOGGED alert=U1-$RUN"
check "no incident"    hasnt "$SANDBOX/incidents.log" "$RUN"

echo "== 2. critical alert, service recovers on the 2nd restart"
e=$(start_alert "F1-$RUN" flaky-api critical);  wait_status "$e" SUCCESS
check "auto-resolved after 2 restarts" has "$SANDBOX/alerts.log" "AUTO-RESOLVED alert=F1-$RUN service=flaky-api restarts=2"
check "nobody paged"   hasnt "$PAGES" "F1-$RUN"
check "no incident"    hasnt "$SANDBOX/incidents.log" "F1-$RUN"

echo "== 3. high alert, service stays down, on-call acknowledges"
e=$(start_alert "D1-$RUN" dead-db high);        await_approval "$e" wait-ack
check "paged (NOTIFY_TERMINAL reached the pager)" has "$PAGES" "PAGE on-call: dead-db still failing after 3 restarts (high, alert D1-$RUN)"
check "incident opened" has "$SANDBOX/incidents.log" "INCIDENT OPENED status=open alert=D1-$RUN service=dead-db severity=high restarts=3"
decide "$e" wait-ack ack approve;               wait_status "$e" SUCCESS
check "ack recorded"   has "$SANDBOX/incidents.log" "INCIDENT ACKNOWLEDGED by on-call | INCIDENT OPENED status=open alert=D1-$RUN"

echo "== 4. critical alert, on-call declines (signal decision \"decline\")"
e=$(start_alert "D2-$RUN" dead-queue critical); await_approval "$e" wait-ack
decide "$e" wait-ack ack decline;               wait_status "$e" SUCCESS
check "decline recorded" has "$SANDBOX/incidents.log" "INCIDENT DECLINED by on-call, paging secondary on-call | INCIDENT OPENED status=open alert=D2-$RUN"

echo "== 4b. a literal reject fails the wait node, so the run fails visibly (onReject is FAIL)"
e=$(start_alert "D4-$RUN" dead-mail critical);  await_approval "$e" wait-ack
decide "$e" wait-ack ack reject;                wait_status "$e" FAILURE
check "incident stays open, no update line" hasnt "$SANDBOX/incidents.log" "ACKNOWLEDGED by on-call | INCIDENT OPENED status=open alert=D4-$RUN"

echo "== 5. critical alert, nobody answers (ack wait shortened from 900 s to 3 s)"
jq '.workflows.alert.nodes |= map(if .nodeId == "wait-ack" then .config.timeoutSeconds = 3 else . end)' session.json > "$TMPD/session.json"
apply_session "$TMPD/session.json"
e=$(start_alert "D3-$RUN" dead-search critical); wait_status "$e" SUCCESS
check "no-ack recorded" has "$SANDBOX/incidents.log" "INCIDENT NO-ACK, paging secondary on-call | INCIDENT OPENED status=open alert=D3-$RUN"

apply_session session.json   # leave the real 900 s wait applied
echo "PASS 08-alert-triage"
