#!/usr/bin/env bash
# Offline test of `yk stats`: it reads the heal events, and its run counts equal the `result` events of the log.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
ev="$tmp/events.jsonl"
cat >"$ev" <<'J'
{"event":"result","run":"r1","workflow":"check","status":"SUCCESS","secs":4}
{"event":"result","run":"r2","workflow":"check","status":"FAILED","secs":10}
{"event":"result","run":"r3","workflow":"check","status":"RUNNING","secs":0}
{"event":"result","run":"r4","workflow":"tool","status":"SUCCESS","secs":2}
{"event":"fix","run":"r2","stage":"tests","attempt":1,"model":"sonnet","fixed":true,"cost":0.5}
{"event":"fix","run":"r2","stage":"tests","attempt":2,"model":"opus","fixed":false,"cost":0.25}
{"event":"fix","run":"r3","stage":"reuse","attempt":1,"model":"auto","fixed":false,"cost":0}
{"event":"stage","run":"r1","stage":"spotless","secs":1}
J
out="$(YUKTA_HEAL_EVENTS="$ev" "$here/yk" stats)"
row() { awk -v k="$1" '$1 == k' <<<"$out" | head -1; }

# runs and failed per kind equal the result events
for kind in check tool; do
  want_runs="$(jq -s --arg k "$kind" '[.[] | select(.event == "result" and .workflow == $k)] | length' "$ev")"
  want_bad="$(jq -s --arg k "$kind" '[.[] | select(.event == "result" and .workflow == $k and .status != "SUCCESS")] | length' "$ev")"
  read -r _ runs bad _ <<<"$(row "$kind")"
  [[ "$runs" == "$want_runs" && "$bad" == "$want_bad" ]] || fail "$kind: runs/failed $runs/$bad, want $want_runs/$want_bad"
done
# median wall seconds of check (4, 10, 0) is 4; its heal dollars are the fixes of its runs
[[ "$(row check | tr -s ' ')" == "check 3 2 4.0 0.75" ]] || fail "check row: $(row check)"
# per stage and rung: attempts, fixed, rate, dollars
[[ "$(awk '$1 == "tests" && $2 == "sonnet"' <<<"$out" | tr -s ' ')" == "tests sonnet 1 1 100% 0.50" ]] || fail "tests/sonnet row missing"
grep -qE '^tests +opus +1 +0 +0% +0.25$' <<<"$out" || fail "tests/opus row missing"
grep -qE '^reuse +auto +1 +0 +0% +0.00$' <<<"$out" || fail "reuse/auto row missing"
echo "ok   stats"
