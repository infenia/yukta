#!/usr/bin/env bash
# Offline test of the decision helpers of _lib.sh (decide, decision_probe) against the fake Ollama: a model that works, a stale and a
# missing one, an unreachable Ollama, a digest over the cap, and YUKTA_DECISION=off. Every failure is a WARN and a return of 1.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$here/scripts/_lib.sh"
probe() { decision_probe || true; }  # the exit status says whether it works; these lines test the text
fail() { echo "FAIL $*" >&2; exit 1; }

dir="$(mktemp -d)"
trap '[[ -z "${pid:-}" ]] || kill "$pid" 2>/dev/null || true; rm -rf "$dir"' EXIT
export DEV_HOME="$dir/home" YK_DECISION_FRESH=1
python3 -I "$here/scripts/fake-ollama.py" "$dir/port" &
pid=$!
for ((i = 0; i < 20; i++)); do [[ -s "$dir/port" ]] && break; sleep 0.25; done
export YUKTA_DECISION_URL="http://127.0.0.1:$(cat "$dir/port")"
crit='{"easy":"a small fix","hard":"needs investigation"}'

export YUKTA_DECISION_MODEL=fake
out="$(decide "difficulty: hard" "How hard?" "$crit")" || fail "a working model gave no answer"
[[ "$out" == "hard 0.7" ]] || fail "answer: $out"
probe | grep '^decision: ok fake' >/dev/null || fail "probe of a working model: $(probe)"

YUKTA_DECISION_MODEL=stale decide "x" "q" "$crit" 2>"$dir/err" && fail "a stale model answered"
grep -q 'WARN decision model unavailable.*ollama pull stale' "$dir/err" || fail "stale: $(cat "$dir/err")"
YUKTA_DECISION_MODEL=stale probe | grep 'no longer supported; run: ollama pull stale' >/dev/null || fail "probe of a stale model"
YUKTA_DECISION_MODEL=missing probe | grep 'is not pulled; run: ollama pull missing' >/dev/null || fail "probe of a missing model"

YUKTA_DECISION_MODEL= decide "x" "q" "$crit" 2>"$dir/err" && fail "no model answered"
grep -q 'none configured' "$dir/err" || fail "no model: $(cat "$dir/err")"
YUKTA_DECISION_MODEL= probe | grep 'not configured; set YUKTA_DECISION_MODEL' >/dev/null || fail "probe without a model"

big="$(head -c 2000 /dev/zero | tr '\0' x)"
decide "$big" "q" "$crit" 2>"$dir/err" && fail "an oversized digest was sent"
grep -q 'over the cap of 1024' "$dir/err" || fail "cap: $(cat "$dir/err")"
YUKTA_DECISION_MAX_INPUT=5000 decide "$big" "q" "$crit" >/dev/null 2>&1 || fail "the cap is not raised by YUKTA_DECISION_MAX_INPUT"

YUKTA_DECISION=off decide "x" "q" "$crit" 2>/dev/null && fail "decide ran with YUKTA_DECISION=off"
YUKTA_DECISION=off probe | grep '^decision: off' >/dev/null || fail "probe with YUKTA_DECISION=off"

YUKTA_DECISION_URL=http://127.0.0.1:1 decide "x" "q" "$crit" 2>"$dir/err" && fail "an unreachable Ollama answered"
grep -q 'not reachable' "$dir/err" || fail "unreachable: $(cat "$dir/err")"

# the probe is kept: a second call within the TTL answers from the cache even when Ollama is gone
unset YK_DECISION_FRESH
decision_probe >/dev/null
kill "$pid"; wait "$pid" 2>/dev/null || true; pid=
probe | grep '^decision: ok fake' >/dev/null || fail "cached probe"
echo "ok   decision"
