#!/usr/bin/env bash
# Offline test of the scheduled pickup (no Claude, no server, no GitHub, about 5 s): pickup.sh tick against the real channel
# (channel/yukta-pickup.py) with a fake gh on PATH, and busy() ignoring the `pickup` schedule.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
tmp="$(mktemp -d)"
cleanup() { exec 3>&- 2>/dev/null || true; for p in ${pid:-} ${spid:-}; do kill "$p" 2>/dev/null || true; done; rm -rf "$tmp"; }
trap cleanup EXIT
freeport() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'; }

# fake gh: answers from files in $FAKE_GH, the way gh prints JSON (the script pipes through jq itself)
mkdir -p "$tmp/bin" "$tmp/gh"
cat >"$tmp/bin/gh" <<'GH'
#!/usr/bin/env bash
case "$1 $2" in
  "issue list") cat "$FAKE_GH/mine.json" ;;
  "project item-list") cat "$FAKE_GH/ready.json" ;;
  "pr list") cat "$FAKE_GH/prs.json" ;;
  "api repos/"*) f="$FAKE_GH/refs-${2##*/}.json"; if [[ -f "$f" ]]; then cat "$f"; else echo '[]'; fi ;;
  *) echo "fake gh: unexpected $*" >&2; exit 9 ;;
esac
GH
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH" FAKE_GH="$tmp/gh" YUKTA_DEV_HOME="$tmp/home" YUKTA_PICKUP_PORT
YUKTA_PICKUP_PORT="$(freeport)"
mkdir -p "$tmp/home"; echo secret >"$tmp/home/pickup-token"
echo '[]' >"$tmp/gh/prs.json"
tick() { "$here/scripts/pickup.sh" tick; }

# 1. no channel
out="$(tick)" || fail "no channel must not be an error: $out"
[[ "$out" == "PICKUP skipped=no channel"* ]] || fail "no channel: $out"

mkfifo "$tmp/in"
python3 -I "$here/channel/yukta-pickup.py" <"$tmp/in" >"$tmp/out" 2>"$tmp/err" &
pid=$!
exec 3>"$tmp/in"
for _ in $(seq 50); do curl -fs "http://127.0.0.1:$YUKTA_PICKUP_PORT/status" >/dev/null 2>&1 && break; sleep 0.1; done
issues() { jq -nc '[{number: 11, title: "low", url: "u11"}, {number: 12, title: "top", url: "u12"}, {number: 13, title: "unready", url: "u13"}]' >"$tmp/gh/mine.json"; }
ready() { jq -nc --argjson i "$1" '{items: $i}' >"$tmp/gh/ready.json"; }
issues

# 2. nothing Ready
ready '[{"status": "Backlog", "priority": "P0", "content": {"number": 12}}]'
out="$(tick)"; [[ "$out" == "PICKUP skipped=nothing assigned"* ]] || fail "nothing Ready: $out"

# 3. Ready: the higher Priority goes first, with the token; 13 (not Ready) is never taken
ready '[{"status":"Ready","priority":"P2","content":{"number":11}},{"status":"Ready","priority":"P1","content":{"number":12}},{"status":"In progress","priority":"P0","content":{"number":13}}]'
out="$(tick)"; [[ "$out" == "PICKUP handed=#12" ]] || fail "handed: $out"
for _ in $(seq 30); do [[ -s "$tmp/out" ]] && break; sleep 0.1; done
jq -e '.method == "notifications/claude/channel" and .params.meta.issue == "12" and .params.meta.url == "u12"' "$tmp/out" >/dev/null || fail "event: $(cat "$tmp/out")"

# 4. busy
out="$(tick)"; [[ "$out" == "PICKUP skipped=busy with #12" ]] || fail "busy: $out"
echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"pickup_done","arguments":{"issue":12,"outcome":"pr"}}}' >&3
for _ in $(seq 30); do [[ "$(wc -l <"$tmp/out")" -ge 2 ]] && break; sleep 0.1; done

# 5. 12 has an open PR (by branch, then by "Fixes #12"), 11 is next; then 11 has a remote issue/11 branch: nothing left
echo '[{"headRefName": "issue/12", "title": "x", "body": ""}]' >"$tmp/gh/prs.json"
out="$(tick)"; [[ "$out" == "PICKUP handed=#11" ]] || fail "PR by branch: $out"
echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"pickup_done","arguments":{"issue":11,"outcome":"blocked"}}}' >&3
for _ in $(seq 30); do [[ "$(wc -l <"$tmp/out")" -ge 4 ]] && break; sleep 0.1; done
echo '[{"headRefName": "feat/a", "title": "feat", "body": "Fixes #12\n"}]' >"$tmp/gh/prs.json"
echo '[{"ref": "refs/heads/issue/11"}]' >"$tmp/gh/refs-11.json"
out="$(tick)"; [[ "$out" == "PICKUP skipped=nothing assigned"* ]] || fail "PR by body + branch on the remote: $out"
echo '[{"headRefName": "feat/a", "title": "feat", "body": "Refs #120"}]' >"$tmp/gh/prs.json"
out="$(tick)"; [[ "$out" == "PICKUP handed=#12" ]] || fail "#120 must not match #12: $out"

# 6. a wrong token is an error, not a skip
mkdir -p "$tmp/home2"; echo wrong >"$tmp/home2/pickup-token"
rpc_done() { echo '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"pickup_done","arguments":{"issue":12,"outcome":"skipped"}}}' >&3; sleep 0.5; }
rpc_done
if out="$(YUKTA_DEV_HOME="$tmp/home2" tick 2>&1)"; then fail "a refused POST must fail: $out"; fi
[[ "$out" == *"answered 403"* ]] || fail "403: $out"

# 7. busy() does not count the pickup schedule (and counts anything else)
cat >"$tmp/srv.py" <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
rows = json.load(open(sys.argv[2]))
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        d = json.dumps({"data": rows}).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(d))); self.end_headers(); self.wfile.write(d)
HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
sport="$(freeport)"
echo '[{"workflowId":"pickup","status":"RUNNING","executionId":"p"},{"workflowId":"tool","status":"SUCCESS","executionId":"a"}]' >"$tmp/exec.json"
python3 "$tmp/srv.py" "$sport" "$tmp/exec.json" &
spid=$!
for _ in $(seq 50); do curl -fs "http://127.0.0.1:$sport/" >/dev/null 2>&1 && break; sleep 0.1; done
n="$(YUKTA_DEV_PORT=$sport bash -c 'source "$1/scripts/_lib.sh"; busy' _ "$here")"
[[ "$n" == 0 ]] || fail "only pickup running: busy must be 0, got $n"
kill "$spid"; wait "$spid" 2>/dev/null || true
echo '[{"workflowId":"pickup","status":"RUNNING","executionId":"p"},{"workflowId":"check","status":"RUNNING","executionId":"c"}]' >"$tmp/exec.json"
python3 "$tmp/srv.py" "$sport" "$tmp/exec.json" &
spid=$!
for _ in $(seq 50); do curl -fs "http://127.0.0.1:$sport/" >/dev/null 2>&1 && break; sleep 0.1; done
n="$(YUKTA_DEV_PORT=$sport bash -c 'source "$1/scripts/_lib.sh"; busy' _ "$here")"
[[ "$n" == 1 ]] || fail "a running check must count: busy got $n"
echo "ok: test-pickup"
