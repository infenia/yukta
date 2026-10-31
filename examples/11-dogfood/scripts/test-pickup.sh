#!/usr/bin/env bash
# Offline test of the scheduled pickup (no Claude, no server, no GitHub, about 15 s): pickup.sh tick against real channels
# (channel/yukta-pickup.py, several at once, found through the registry) with a fake gh on PATH, and busy() ignoring the `pickup` schedule.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
tmp="$(mktemp -d)"
cleanup() { for p in "${PID[@]:-}" ${spid:-}; do [[ -z "$p" ]] || kill "$p" 2>/dev/null || true; done; rm -rf "$tmp"; }
declare -A FD PID
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
export PATH="$tmp/bin:$PATH" FAKE_GH="$tmp/gh" YUKTA_DEV_HOME="$tmp/home"
mkdir -p "$tmp/home"; echo secret >"$tmp/home/pickup-token"
echo '[]' >"$tmp/gh/prs.json"
tick() { "$here/scripts/pickup.sh" tick; }

# channels: each one is a real yukta-pickup.py with its own stdin fifo (to send pickup_done) and registry entry
start_channel() {
  local name="$1"
  mkfifo "$tmp/in-$name"
  YUKTA_PICKUP_NAME="$name" python3 -I "$here/channel/yukta-pickup.py" <"$tmp/in-$name" >"$tmp/out-$name" 2>"$tmp/err-$name" &
  PID[$name]=$!
  exec {fd}>"$tmp/in-$name"
  FD[$name]=$fd
  for _ in $(seq 50); do [[ -f "$tmp/home/pickup/$name.json" ]] && break; sleep 0.1; done
  [[ -f "$tmp/home/pickup/$name.json" ]] || fail "channel $name did not register: $(cat "$tmp/err-$name")"
}
done_for() { echo "{\"jsonrpc\":\"2.0\",\"id\":$3,\"method\":\"tools/call\",\"params\":{\"name\":\"pickup_done\",\"arguments\":{\"issue\":$2,\"outcome\":\"pr\"}}}" >&"${FD[$1]}"; sleep 0.4; }
issues() { jq -nc '[{number: 11, title: "low", url: "u11"}, {number: 12, title: "top", url: "u12"}, {number: 13, title: "unready", url: "u13"}, {number: 14, title: "later", url: "u14"}]' >"$tmp/gh/mine.json"; }
# the real project has about 1200 items (2.4 MB of JSON): pad every answer so it is far past the 128 KB one jq argument can hold
ready() { jq -nc --argjson i "$1" '{items: ($i + [range(5000) | {status: "Done", priority: "P3", title: ("x" * 60), content: {number: (100000 + .)}}])}' >"$tmp/gh/ready.json"; }
issues

# 1. no channel (an empty registry, and one whose only entry is a dead pid: dropped)
out="$(tick)" || fail "no channel must not be an error: $out"
[[ "$out" == "PICKUP skipped=no channel"* ]] || fail "no channel: $out"
mkdir -p "$tmp/home/pickup"; echo '{"name":"ghost","port":1,"pid":999999,"started":1}' >"$tmp/home/pickup/ghost.json"
out="$(tick)"; [[ "$out" == "PICKUP skipped=no channel"* ]] || fail "dead pid: $out"
[[ ! -e "$tmp/home/pickup/ghost.json" ]] || fail "the entry of a dead pid must be deleted"

start_channel a; sleep 0.2; start_channel b

# 2. nothing Ready
ready '[{"status": "Backlog", "priority": "P0", "content": {"number": 12}}]'
out="$(tick)"; [[ "$out" == "PICKUP skipped=nothing assigned"* ]] || fail "nothing Ready: $out"

# 3. two Ready issues, two idle sessions: the higher Priority to the oldest session, the next to the other; 13 (not Ready) never
ready '[{"status":"Ready","priority":"P2","content":{"number":11}},{"status":"Ready","priority":"P1","content":{"number":12}},{"status":"In progress","priority":"P0","content":{"number":13}}]'
out="$(tick)"; [[ "$out" == $'PICKUP handed=#12 to=a\nPICKUP handed=#11 to=b' ]] || fail "two sessions: $out"
for _ in $(seq 30); do [[ -s "$tmp/out-a" && -s "$tmp/out-b" ]] && break; sleep 0.1; done
jq -e '.method == "notifications/claude/channel" and .params.meta.issue == "12"' "$tmp/out-a" >/dev/null || fail "event of a: $(cat "$tmp/out-a")"
jq -e '.params.meta.issue == "11"' "$tmp/out-b" >/dev/null || fail "event of b: $(cat "$tmp/out-b")"

# 4. both busy (the issues are In progress now, as the skill claims them)
ready '[{"status":"In progress","priority":"P1","content":{"number":12}},{"status":"In progress","priority":"P2","content":{"number":11}},{"status":"Ready","priority":"P3","content":{"number":14}}]'
out="$(tick)"; [[ "$out" == "PICKUP skipped=all busy (a,b)" ]] || fail "all busy: $out"

# 5. a reports back: the next Ready issue goes to a, b stays busy
done_for a 12 2
out="$(tick)"; [[ "$out" == "PICKUP handed=#14 to=a" ]] || fail "after a is free: $out"
done_for a 14 3

# 6. a Ready issue with an open PR (by branch, by "Fixes #n", but not "#140") or an issue/<n> branch is skipped
ready '[{"status":"Ready","priority":"P1","content":{"number":12}}]'
echo '[{"headRefName": "issue/12", "title": "x", "body": ""}]' >"$tmp/gh/prs.json"
out="$(tick)"; [[ "$out" == "PICKUP skipped=nothing assigned"* ]] || fail "PR by branch: $out"
echo '[{"headRefName": "feat/a", "title": "feat", "body": "Fixes #12\n"}]' >"$tmp/gh/prs.json"
out="$(tick)"; [[ "$out" == "PICKUP skipped=nothing assigned"* ]] || fail "PR by body: $out"
echo '[]' >"$tmp/gh/prs.json"; echo '[{"ref": "refs/heads/issue/12"}]' >"$tmp/gh/refs-12.json"
out="$(tick)"; [[ "$out" == "PICKUP skipped=nothing assigned"* ]] || fail "branch on the remote: $out"
rm "$tmp/gh/refs-12.json"; echo '[{"headRefName": "feat/a", "title": "feat", "body": "Refs #120"}]' >"$tmp/gh/prs.json"
out="$(tick)"; [[ "$out" == "PICKUP handed=#12 to=a" ]] || fail "#120 must not match #12: $out"
done_for a 12 4; echo '[]' >"$tmp/gh/prs.json"

# 7. --to / YUKTA_PICKUP_ONLY pins one session: b is busy, so nothing goes anywhere
out="$(YUKTA_PICKUP_ONLY=b tick)"; [[ "$out" == "PICKUP skipped=all busy (b)" ]] || fail "pinned to a busy session: $out"
out="$(YUKTA_PICKUP_ONLY=zzz tick)"; [[ "$out" == "PICKUP skipped=no channel named zzz"* ]] || fail "pinned to an unknown session: $out"
done_for b 11 5
out="$(YUKTA_PICKUP_ONLY=b tick)"; [[ "$out" == "PICKUP handed=#12 to=b" ]] || fail "pinned to b: $out"
done_for b 12 6

# 8. a killed channel (no exit hook) is dropped and its file deleted; a closed one removes itself
kill -9 "${PID[a]}"; wait "${PID[a]}" 2>/dev/null || true
out="$(tick)"; [[ "$out" == "PICKUP handed=#12 to=b" ]] || fail "after a was killed: $out"
[[ ! -e "$tmp/home/pickup/a.json" ]] || fail "the entry of the killed channel must be deleted"
exec {FD[b]}>&-
for _ in $(seq 50); do kill -0 "${PID[b]}" 2>/dev/null || break; sleep 0.1; done
[[ ! -e "$tmp/home/pickup/b.json" ]] || fail "a closed session must remove its entry"
out="$(tick)"; [[ "$out" == "PICKUP skipped=no channel"* ]] || fail "all closed: $out"

# 9. a wrong token is an error, not a skip (same registry, another token)
start_channel c
mkdir -p "$tmp/home2"; echo wrong >"$tmp/home2/pickup-token"; ln -sfn "$tmp/home/pickup" "$tmp/home2/pickup"
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
