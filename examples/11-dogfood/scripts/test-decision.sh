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
export DEV_HOME="$dir/home" YK_DECISION_FRESH=1 STATE_DIR="$dir/state" YUKTA_DECISION=on
python3 -I "$here/scripts/fake-ollama.py" "$dir/port" &
pid=$!
for ((i = 0; i < 20; i++)); do [[ -s "$dir/port" ]] && break; sleep 0.25; done
export YUKTA_DECISION_URL="http://127.0.0.1:$(cat "$dir/port")"
crit='{"easy":"a small fix","hard":"needs investigation"}'

export YUKTA_DECISION_MODEL=fake
out="$(decide t "difficulty: hard" "How hard?" "$crit")" || fail "a working model gave no answer"
[[ "$out" == "hard 0.7" ]] || fail "answer: $out"
probe | grep '^decision: ok fake' >/dev/null || fail "probe of a working model: $(probe)"

YUKTA_DECISION_MODEL=stale decide t "x" "q" "$crit" 2>"$dir/err" && fail "a stale model answered"
grep -q 'WARN decision model unavailable.*ollama pull stale' "$dir/err" || fail "stale: $(cat "$dir/err")"
YUKTA_DECISION_MODEL=stale probe | grep 'no longer supported; run: ollama pull stale' >/dev/null || fail "probe of a stale model"
YUKTA_DECISION_MODEL=missing probe | grep 'is not pulled; run: ollama pull missing' >/dev/null || fail "probe of a missing model"

YUKTA_DECISION_MODEL= decide t "x" "q" "$crit" 2>"$dir/err" && fail "no model answered"
grep -q 'none configured' "$dir/err" || fail "no model: $(cat "$dir/err")"
YUKTA_DECISION_MODEL= probe | grep 'not configured; set YUKTA_DECISION_MODEL' >/dev/null || fail "probe without a model"

big="$(head -c 2000 /dev/zero | tr '\0' x)"
decide t "$big" "q" "$crit" 2>"$dir/err" && fail "an oversized digest was sent"
grep -q 'over the cap of 1024' "$dir/err" || fail "cap: $(cat "$dir/err")"
YUKTA_DECISION_MAX_INPUT=5000 decide t "$big" "q" "$crit" >/dev/null 2>&1 || fail "the cap is not raised by YUKTA_DECISION_MAX_INPUT"

YUKTA_DECISION=off decide t "x" "q" "$crit" 2>/dev/null && fail "decide ran with YUKTA_DECISION=off"
YUKTA_DECISION=off probe | grep '^decision: off' >/dev/null || fail "probe with YUKTA_DECISION=off"

YUKTA_DECISION_URL=http://127.0.0.1:1 decide t "x" "q" "$crit" 2>"$dir/err" && fail "an unreachable Ollama answered"
grep -q 'not reachable' "$dir/err" || fail "unreachable: $(cat "$dir/err")"

# shadow (the default): the model is asked and the answer recorded, but the caller's path decides (return 1)
YUKTA_DECISION=shadow decide shadowed "difficulty: hard" "How hard?" "$crit" >"$dir/out" && fail "shadow mode returned an answer"
[[ ! -s "$dir/out" ]] || fail "shadow mode printed an answer"
decision_outcome shadowed "difficulty: hard" hard
YUKTA_DECISION=shadow decide shadowed "difficulty: easy" "How hard?" "$crit" || true
decision_outcome shadowed "difficulty: easy" hard
# one decision can be on while the rest is off
YUKTA_DECISION=off YUKTA_DECISION_SHADOWED=on decide shadowed "difficulty: hard" "How hard?" "$crit" >/dev/null || fail "a decision set to on stayed off"
report="$(python3 -I "$here/scripts/decisions-report.py" "$STATE_DIR/decisions.tsv" "$here/decisions.json")"
grep 'shadowed: 3 decisions, 3 with an outcome, agreement 2/3 = 67%' <<<"$report" >/dev/null || fail "report: $report"
grep 'stays in shadow' <<<"$report" >/dev/null || fail "report has no verdict: $report"
# the probe is kept: a second call within the TTL answers from the cache even when Ollama is gone
unset YK_DECISION_FRESH
decision_probe >/dev/null
kill "$pid"; wait "$pid" 2>/dev/null || true; pid=
probe | grep '^decision: ok fake' >/dev/null || fail "cached probe"
echo "ok   decision"
