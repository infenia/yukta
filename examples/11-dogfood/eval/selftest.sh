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

# ---- arm C: the orchestrator is told --nav, the result records the backend and the loop commit
"$here/eval.sh" run C 1 >"$dir/c.out" 2>/dev/null
c="$dir/runs/C1/result.json"
[[ "$(jq -r .arm "$c")" == C && "$(jq -r .nav.backend "$c")" == cbm && "$(jq -r .nav.ok "$c")" == true ]] || fail "arm C result: $(jq -c '{arm, nav}' "$c")"
[[ -n "$(jq -r .loop_sha "$c")" && "$(jq -r .loop_sha "$c")" != null ]] || fail "no loop commit in the result"
[[ "$(jq -r .nav "$dir/runs/B1/result.json")" == null ]] || fail "arm B must record nav null"
[[ "$(jq -r .sub.attempts "$c")" == 3 ]] || fail "arm C is arm B plus an index: $(jq -c .sub "$c")"

# ---- validate: a sound grader fails on the base and passes with the fix; a weak one is refused
vi="$dir/issues"
mkdir -p "$vi/501" "$vi/502"
for n in 501 502; do cp "$here/issues/selftest/issue.json" "$vi/$n/issue.json"; echo "x" >"$vi/$n/hidden_test.go"
  cat >"$vi/$n/reference-fix.patch" <<'PATCH'
diff --git a/marker.txt b/marker.txt
new file mode 100644
--- /dev/null
+++ b/marker.txt
@@ -0,0 +1 @@
+fixed
PATCH
done
printf 'MODULE=cli\nHIDDEN_DEST=eval-hidden.txt\nHIDDEN_CMD=%s\nSUITE_CMD=true\n' "'test -f marker.txt'" >"$vi/501/grade.env"   # fails until the fix creates marker.txt
printf 'MODULE=cli\nHIDDEN_DEST=eval-hidden.txt\nHIDDEN_CMD=true\nSUITE_CMD=true\n' >"$vi/502/grade.env"                          # passes on the base too: worthless
v="$(EVAL_ISSUES_DIR="$vi" EVAL_ISSUE=501 "$here/eval.sh" validate 2>&1)" || fail "a sound grader was refused: $v"
[[ "$(jq -r .sound <<<"$(grep -v '^$' <<<"$v" | tr -d '\n' | sed 's/^[^{]*//')")" == true ]] || fail "validate verdict: $v"
v="$(EVAL_ISSUES_DIR="$vi" EVAL_ISSUE=502 "$here/eval.sh" validate 2>&1)" && fail "a grader that passes on the base commit was accepted: $v"
grep -q 'the grader is not sound' <<<"$v" || fail "validate message: $v"
echo "ok   eval harness"
