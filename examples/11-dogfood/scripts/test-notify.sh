#!/usr/bin/env bash
# Offline test of yk --notify (no Claude, no server, no cost, about 2 s): notify_session writes the auth line and the user line to a Unix
# socket, does nothing without a socket or with YUKTA_NOTIFY=off, never fails; notify_text builds the message of a finished run; the flags
# are checked before anything starts.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
tmp="$(mktemp -d)"
trap '[[ -z "${spid:-}" ]] || kill "$spid" 2>/dev/null || true; rm -rf "$tmp"' EXIT
export YUKTA_DEV_HOME="$tmp/home"
# shellcheck source=/dev/null
source "$here/scripts/_lib.sh"
# never talk to the real session this test may run in
unset CLAUDE_CODE_MESSAGING_SOCKET CLAUDE_CODE_MESSAGING_TOKEN

# a stub of the session's inbox socket: collects every line it is sent
cat >"$tmp/sock.py" <<'PY'
import os, socket, sys
path, out = sys.argv[1:3]
s = socket.socket(socket.AF_UNIX)
s.bind(path)
s.listen(5)
open(out + ".ready", "w").close()
while True:
    c, _ = s.accept()
    with c, open(out, "ab") as f:
        f.write(c.makefile("rb").read())
PY
sock="$tmp/s.sock"; got="$tmp/got"
python3 -I "$tmp/sock.py" "$sock" "$got" &
spid=$!
for _ in $(seq 50); do [[ -e "$got.ready" ]] && break; sleep 0.1; done
wait_lines() { for _ in $(seq 50); do [[ "$(wc -l <"$got" 2>/dev/null || echo 0)" -ge "$1" ]] && return 0; sleep 0.1; done; fail "expected $1 lines, got: $(cat "$got" 2>/dev/null)"; }

# 1. the auth line, then the user line with the text
CLAUDE_CODE_MESSAGING_SOCKET="$sock" CLAUDE_CODE_MESSAGING_TOKEN=tok123 notify_session $'[yk] hello\nsecond line "quoted"'
wait_lines 2
jq -e '.type == "auth" and .token == "tok123"' < <(sed -n 1p "$got") >/dev/null || fail "auth line: $(sed -n 1p "$got")"
jq -e '.type == "user" and .message.role == "user" and .message.content == "[yk] hello\nsecond line \"quoted\""' < <(sed -n 2p "$got") >/dev/null || fail "user line: $(sed -n 2p "$got")"

# 2. no token: just the user line; long text is cut at 8000 characters
: >"$got"
CLAUDE_CODE_MESSAGING_SOCKET="$sock" notify_session "$(head -c 20000 /dev/zero | tr '\0' x)"
wait_lines 1
[[ "$(wc -l <"$got")" == 1 ]] || fail "no token: one line expected, got $(wc -l <"$got") lines: $(cut -c1-80 "$got")"
jq -e '.type == "user" and (.message.content | length) == 8000' <"$got" >/dev/null || fail "text must be cut at 8000"

# 3. silent and successful: no socket, YUKTA_NOTIFY=off, a socket nobody listens on
: >"$got"
env -u CLAUDE_CODE_MESSAGING_SOCKET bash -c "source '$here/scripts/_lib.sh'; notify_session x" || fail "no socket must succeed"
CLAUDE_CODE_MESSAGING_SOCKET="$sock" YUKTA_NOTIFY=off notify_session x || fail "off must succeed"
sleep 0.3; [[ ! -s "$got" ]] || fail "off must send nothing: $(cat "$got")"
CLAUDE_CODE_MESSAGING_SOCKET="$tmp/nobody.sock" notify_session x 2>"$tmp/err" || fail "a dead socket must not fail the caller"
grep -q '^notify: ' "$tmp/err" || fail "a dead socket should say so on stderr"

# 4. the message of a finished run
cat >"$tmp/log" <<'LOG'
WARN fast: something harmless
ERROR style/pmd: core/src/A.java:12 UnusedLocalVariable
OUTPUT tests: 3 tests failed
MORE yk detail dogfood e1
RESULT status=FAILED failed_node=style healed=- execution=e1 session=dogfood wall=3 cost=$0 bytes=10
LOG
txt="$(notify_text dogfood e1 "$tmp/log")"
[[ "$txt" == "[yk] run finished: RESULT status=FAILED failed_node=style"* ]] || fail "text head: $txt"
[[ "$txt" == *"ERROR style/pmd: core/src/A.java:12 UnusedLocalVariable"* && "$txt" == *"OUTPUT tests: 3 tests failed"* ]] || fail "problem lines: $txt"
[[ "$txt" != *"harmless"* && "$txt" == *"More: yk detail dogfood e1" ]] || fail "text tail: $txt"
printf 'ERROR x: boom\n' >"$tmp/log2"
txt="$(notify_text dogfood e2 "$tmp/log2")"
[[ "$txt" == "[yk] run e2 ended without a RESULT line"* ]] || fail "no RESULT: $txt"

# 5. the flags: --notify needs --detach, checked before any server is touched (an unreachable server URL proves it)
for c in "gradle --notify :core:compileJava" "tool --notify true" "check --notify"; do
  # shellcheck disable=SC2086
  out="$(YUKTA_SERVER_URL=http://127.0.0.1:1 "$here/yk" $c 2>&1)" && fail "yk $c must be refused"
  [[ "$out" == *"--notify needs --detach"* ]] || fail "yk $c: $out"
done
echo "ok: test-notify"
