#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Smoke test the run modes of a server binary: the daemon commands (start, status, stop) and the
# interactive console on a pseudo-terminal (dashboard drawn, q exits with status 0). Both are
# native-only risks: terminal detection, JLine and AOT-frozen conditions. Linux only (needs python3).
# Runs in nightly checks (native-smoke.yml), not on pull requests: it needs the native binary.
#
# Usage: release/console-smoke.sh <path-to-yukta-binary>
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 1 && -x "$1" ]] || die "usage: console-smoke.sh <yukta-binary>"
bin="$(realpath "$1")"
home="$(mktemp -d)"
trap 'rm -rf "$home"' EXIT
export YUKTA_HOME="$home" SERVER_PORT=19859

out="$("$bin" start)" || die "console smoke: start failed: $out"
status="$("$bin" status)" || die "console smoke: status failed: $status"
grep -q 'health: UP' <<< "$status" || die "console smoke: not healthy: $status"
"$bin" stop > /dev/null || die "console smoke: stop failed"
set +e
"$bin" status > /dev/null
[[ $? -eq 3 ]] || die "console smoke: status after stop should exit 3"
set -e
echo "daemon: start, status, stop ok"

SERVER_PORT=19860 python3 -I - "$bin" <<'PY'
import os, pty, re, select, sys, time

pid, fd = pty.fork()
if pid == 0:
    os.environ["TERM"] = "xterm"
    os.execv(sys.argv[1], [sys.argv[1]])  # no flags: the mode must be detected from the terminal
buf = b""

def pump(until, seconds):
    global buf
    end = time.time() + seconds
    while time.time() < end and not until():
        if select.select([fd], [], [], 0.2)[0]:
            try:
                buf += os.read(fd, 65536)
            except OSError:
                return

pump(lambda: b"health UP" in buf, 60)
if b"health UP" not in buf:
    text = re.sub(rb"\x1b\[[0-9;?]*[a-zA-Z]", b"", buf).decode(errors="replace")
    sys.exit("console smoke: no dashboard on the terminal:\n" + text[:1200])
os.write(fd, b"q")
end = time.time() + 60
while time.time() < end:
    done, status = os.waitpid(pid, os.WNOHANG)
    if done:
        if os.waitstatus_to_exitcode(status) != 0:
            sys.exit("console smoke: q exited with status %d" % os.waitstatus_to_exitcode(status))
        print("console: dashboard drawn, q exited 0")
        sys.exit(0)
    pump(lambda: False, 0.5)
os.kill(pid, 9)
sys.exit("console smoke: still running 60 s after q")
PY
