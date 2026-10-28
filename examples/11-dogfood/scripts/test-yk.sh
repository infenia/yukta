#!/usr/bin/env bash
# Online test of how yk runs end and report (needs the dev server: yk up; no Claude, no cost, about 30 s):
#   interrupt   a foreground yk stops its execution when it is stopped (SIGTERM, as Claude Code does to a background task)
#   detach      a detached run outlives its client
#   stages      yk watch --stages prints a "STAGE <name> finished" line
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
yk="$here/yk"
fail() { echo "FAIL $*" >&2; exit 1; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
exec_of() { sed -n 's/.*STARTED execution=\([^ ]*\).*/\1/p' "$1" | head -1; }
state_of() { "$yk" tree "$1" | head -1; }
wait_for() { local n="$1"; shift; for ((i = 0; i < n; i++)); do "$@" && return 0; sleep 1; done; return 1; }

# interrupt: SIGTERM to the foreground client ends the run
"$yk" tool sleep 120 >"$tmp/fg.out" 2>&1 &
client=$!
wait_for 30 grep -q STARTED "$tmp/fg.out" || fail "interrupt: the run did not start"
e="$(exec_of "$tmp/fg.out")"
kill -TERM "$client"
wait "$client" 2>/dev/null && fail "interrupt: the client exited 0" || true
wait_for 60 bash -c "[[ \"\$('$yk' tree '$e' | head -1)\" == *STOPPED* ]]" || fail "interrupt: the run is $(state_of "$e")"
echo "ok   interrupt: $(state_of "$e")"

# detach: the run outlives the client that started it
out="$("$yk" tool --detach sleep 20 2>&1)"
e="$(exec_of <(echo "$out"))"
[[ "$(state_of "$e")" == *RUNNING* ]] || fail "detach: not running: $(state_of "$e")"
"$yk" stop "$e" >/dev/null
echo "ok   detach"

# concurrent: a second yk tool/gradle in the same checkout is not refused, it runs (queued by the server's tool group when it is full)
out="$("$yk" tool --detach sleep 6 2>&1)"; e1="$(exec_of <(echo "$out"))"
out2="$("$yk" tool echo concurrent 2>&1)" || { echo "$out2" >&2; fail "concurrent: second run refused"; }
grep -q '^RESULT status=SUCCESS' <<<"$out2" || { echo "$out2" >&2; fail "concurrent: second run did not finish"; }
"$yk" stop "$e1" >/dev/null 2>&1 || true
echo "ok   concurrent"

# levels: a failing run drops the known noise, names the next level, and yk detail returns the full text
out="$("$yk" tool sh -c 'echo "BUILD FAILED in 4s"; echo "Foo.java:3: error: boom"; echo "second line"; exit 1' 2>&1)" || true
grep -q 'BUILD FAILED' <<<"$out" && fail "levels: noise line kept: $out"
grep -q 'Foo.java:3: error: boom' <<<"$out" || fail "levels: the error line is missing: $out"
more="$(sed -n 's/^MORE yk detail \([^ ]*\) \([^ ]*\) .*/\1 \2/p' <<<"$out")"
[[ -n "$more" ]] || fail "levels: no MORE line: $out"
[[ "$("$yk" detail $more --grep boom)" == *"error: boom"* ]] || fail "levels: detail --grep"
[[ "$("$yk" detail $more --tail 1)" == "second line" ]] || fail "levels: detail --tail"
[[ "$("$yk" tool true | tail -1)" == RESULT* ]] || fail "levels: the last line is not RESULT"
echo "ok   levels"

# stages: a progress line without --all
out="$("$yk" tool --detach sleep 2 2>&1)"
e="$(exec_of <(echo "$out"))"
s="$(sed -n 's/.*session=\([^ ]*\).*/\1/p' <<<"$out" | tail -1)"
"$yk" watch "$s" "$e" --stages >"$tmp/watch.out" 2>&1 || true
grep -q '^STAGE run finished$' "$tmp/watch.out" || { cat "$tmp/watch.out" >&2; fail "stages: no STAGE line"; }
grep -q '^RESULT status=SUCCESS' "$tmp/watch.out" || fail "stages: no RESULT line"
# bytes= is what the watch printed (the STAGE lines and the RESULT line), wall= and cost= are there, the events log got a result, yk stats reads it
[[ "$(sed -n 's/^RESULT .* bytes=\([0-9]*\)$/\1/p' "$tmp/watch.out")" == "$(wc -c <"$tmp/watch.out" | tr -d ' ')" ]] || { cat "$tmp/watch.out" >&2; fail "bytes: does not match the output"; }
grep -qE '^RESULT .* wall=[0-9.]+ cost=\$[0-9.]+ bytes=[0-9]+$' "$tmp/watch.out" || fail "result: no wall, cost, bytes"
[[ "$("$yk" stats)" == *median_wall* ]] || fail "stats"
echo "ok   stages"
