#!/usr/bin/env bash
# Offline test of the eval harness: a fake claude, a dummy issue, no server and no spend. It checks the run
# directory, the stubs, the collection of main-session and sub-flow usage, grading, split.sh and the report.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
export EVAL_DIR="$dir" EVAL_ISSUE=selftest EVAL_CLAUDE="$here/fake-claude.sh" EVAL_YK=/bin/true
EVAL_BASE="$(git -C "$here" rev-parse HEAD)"; export EVAL_BASE

"$here/eval.sh" run A 1 >"$dir/a.out" 2>/dev/null
"$here/eval.sh" run B 1 >"$dir/b.out" 2>/dev/null

a="$dir/runs/A1/result.json" b="$dir/runs/B1/result.json"
[[ "$(jq -r '.total.cost' "$a")" == 2.5 ]] || fail "arm A cost: $(jq -c .total "$a")"
[[ "$(jq -r '.total.cache_read' "$a")" == 600000 ]] || fail "arm A cache read"
[[ "$(jq -r '.sub.attempts' "$a")" == 0 ]] || fail "arm A has sub-flow records"
# arm B: orchestrator 0.2 + implementer 0.9 + two fixers 0.1 and 0.2
awk -v c="$(jq -r '.total.cost' "$b")" 'BEGIN { exit !(c > 1.39 && c < 1.41) }' || fail "arm B cost: $(jq -c .total "$b")"
[[ "$(jq -r '.sub.attempts' "$b")" == 3 ]] || fail "arm B attempts (implementer and two fixers): $(jq -c .sub "$b")"
[[ "$(jq -r '.sub.cache_read' "$b")" == 48000 ]] || fail "arm B sub-flow cache read: $(jq -c .sub "$b")"
[[ "$(jq -r '.main.turns' "$b")" == 4 ]] || fail "arm B main turns"
for r in "$a" "$b"; do
  [[ "$(jq -r '.grade.hidden_pass' "$r")" == true && "$(jq -r '.grade.suite_pass' "$r")" == true ]] || fail "grading: $(jq -c .grade "$r")"
done
[[ ! -e "$dir/runs/A1/yukta/eval-selftest-hidden.txt" ]] || fail "the hidden test was left in the clone"
[[ "$(cat "$dir/runs/A1/base")" == "$EVAL_BASE" ]] || fail "the clone is not on the base commit"
[[ "$(git -C "$dir/runs/A1/yukta" rev-parse HEAD)" == "$EVAL_BASE" ]] || fail "the clone is not on the base commit"

out="$("$here/eval.sh" report)"
grep -q '^| A1 ' <<<"$out" && grep -q '^| B1 ' <<<"$out" || fail "report rows: $out"
grep -q '^| A | 1 | 1/1 ' <<<"$out" || fail "report per-arm row: $out"

split="$("$here/split.sh")"
grep -q '^B1 ' <<<"$split" || fail "split rows: $split"
# both fixers are outside cli/ (one changed core/X.java, one nothing): 0.30 of the 1.40 total
[[ "$(awk '/^B1 /{print $4}' <<<"$split")" == 0.30 ]] || fail "split fix-other: $split"
echo "ok   eval harness"
