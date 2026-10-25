#!/usr/bin/env bash
# Offline test of yk loop themes: rule counts without a model, themes from a (fake) judge whose counts and dollars are computed here,
# an invented run id counting for nothing, --post once per change, the loop filter, and a judge that fails. No network, no spend.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
mkdir -p "$dir/loop/analysis" "$dir/repo"
export YUKTA_LOOP_DIR="$dir/loop" YUKTA_REPO_ROOT="$dir/repo" YUKTA_GH="$dir/gh" YUKTA_CLAUDE_BIN="$dir/claude" YUKTA_LOOP_BUDGET_TOTAL=60
loop="$here/loop.sh"

analysis() { # RUN METHOD COST SHA NOTE RULES...
  local run="$1" method="$2" cost="$3" sha="$4" note="$5"
  shift 5
  jq -n --arg run "$run" --arg m "$method" --argjson c "$cost" --arg sha "$sha" --arg note "$note" --argjson rules "$(printf '%s\n' "$@" | jq -R . | jq -sc 'map(select(. != ""))')" \
    '{facts: {run: $run, method: $m, size: "S", cost_usd: $c, loop_sha: $sha}, findings: ($rules | map({rule: ., evidence: "e"})), note: $note, filed: [], analyzer_cost_usd: 0.07}' >"$YUKTA_LOOP_DIR/analysis/$run.json"
}
analysis r1 C 4.0 aaa111 "Fixers spent most of the money on tests in other modules." noop-fixers fixer-outside-module
analysis r2 C 2.0 aaa111 "Two fixer attempts on failures the change did not cause." noop-fixers
analysis r3 C 1.0 aaa111 "A fixer edited tests outside the module." fixer-outside-module
analysis r4 A 0.5 bbb222 "The session re-ran the whole gate three times."
analysis r5 A 0.5 bbb222 "The session re-ran the whole gate again."
analysis r6 C 0.3 bbb222 ""

cat >"$dir/gh" <<'SH'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")" && pwd)"
echo "$*" >>"$here/gh.log"
case "$1 $2" in
  "api repos/infenia/yukta/issues/1218/sub_issues") printf '#1256 Fixers in an issue run resume the implementer on Opus\n#1234 A fixer can change files outside the issue\n' ;;
  "issue view") echo OPEN ;;
  "issue comment") ;;
  *) echo "fake gh: unsupported: $*" >&2; exit 1 ;;
esac
SH
cat >"$dir/claude" <<'SH'
#!/usr/bin/env bash
echo called >>"$(dirname "$0")/claude.log"
[[ -e "$(dirname "$0")/judge-fails" ]] && exit 1
jq -nc '{total_cost_usd: 0.11, structured_output: {themes: [
  {name: "Fixers repair what the change did not break", description: "fixer attempts outside the issue", runs: ["r1", "r2", "r3", "r-invented"], issue: 1256},
  {name: "The solo session repeats the gate", description: "whole gate run again and again", runs: ["r4", "r5"], issue: null}]}}'
SH
chmod +x "$dir/gh" "$dir/claude"

# ---- the rules need no model; the themes come from the judge but the numbers are ours
out="$("$loop" themes)" || fail "themes failed: $out"
grep -qE '^\| noop-fixers \| 2 of 6 \| \$6 \| #1256, #1249 \|' <<<"$out" || fail "noop-fixers row (r1 and r2: \$4 + \$2, issues from analyzer-known.json): $(sed -n '/## Rules/,/## Themes/p' <<<"$out")"
grep -qE '^\| fixer-outside-module \| 2 of 6 \| \$5 \| #1234 \|' <<<"$out" || fail "fixer-outside-module row: $(sed -n '/## Rules/,/## Themes/p' <<<"$out")"
grep -qE '^\| Fixers repair what the change did not break: fixer attempts outside the issue \| 3 \| \$7 \| #1256 \|' <<<"$out" || fail "theme 1 must count 3 runs and \$7 (the invented run id counts for nothing): $(sed -n '/## Themes/,$p' <<<"$out")"
grep -qE '^\| The solo session repeats the gate: whole gate run again and again \| 2 \| \$1 \| - \|' <<<"$out" || fail "theme 2: $(sed -n '/## Themes/,$p' <<<"$out")"
[[ "$(grep -n 'Fixers repair' <<<"$out" | cut -d: -f1)" -lt "$(grep -n 'solo session repeats' <<<"$out" | cut -d: -f1)" ]] || fail "themes must be ranked by runs x dollars"
grep -q 'Judge cost \$0.11' <<<"$out" || fail "judge cost"
[[ "$(jq -s '[.[] | select(.type == "themes")] | length' "$YUKTA_LOOP_DIR/ledger.jsonl")" == 1 && "$("$loop" total)" == '$0.11 of $60' ]] || fail "the judge's cost is not in the loop's total: $("$loop" total)"
[[ "$(jq -r '.themes | length' "$(ls "$YUKTA_LOOP_DIR"/themes/2*.json | head -1)")" == 2 && "$(jq '.themes[0].runs | length' "$(ls "$YUKTA_LOOP_DIR"/themes/2*.json | head -1)")" == 3 ]] || fail "the themes file"
echo "ok   rules and themes"

# ---- --no-judge, too few notes, a judge that fails
: >"$dir/claude.log"
out="$("$loop" themes --no-judge)"
grep -q 'Skipped (--no-judge)' <<<"$out" && [[ ! -s "$dir/claude.log" ]] || fail "--no-judge called the judge: $(cat "$dir/claude.log")"
grep -q 'noop-fixers' <<<"$out" || fail "the rules are still shown with --no-judge"
out="$("$loop" themes --loop bbb222)"
grep -q 'Not enough notes yet: 2' <<<"$out" || fail "--loop bbb222 has 2 notes: $(sed -n '/## Themes/,$p' <<<"$out")"
grep -q 'of 3 analyzed' <<<"$(sed -n 1p <<<"$out")" || grep -q 'Themes of 3 analyzed run(s) (loop bbb222)' <<<"$out" || fail "the loop filter: $(sed -n 1p <<<"$out")"
touch "$dir/judge-fails"
out="$("$loop" themes)" || fail "a failing judge broke themes: $out"
grep -q 'The judge found no recurring theme' <<<"$out" && grep -q 'noop-fixers' <<<"$out" || fail "a failing judge: $(sed -n '/## Themes/,$p' <<<"$out")"
rm -f "$dir/judge-fails"
echo "ok   no judge, few notes, failing judge"

# ---- --post: the counts go on the matching open issue, once per change
: >"$dir/gh.log"
"$loop" themes --post >"$dir/post1" 2>&1 || fail "--post failed: $(cat "$dir/post1")"
[[ "$(grep -c '^issue comment 1256 ' "$dir/gh.log")" == 1 ]] || fail "one comment on #1256 expected: $(cat "$dir/gh.log")"
grep -q 'shows in 3 run(s) that cost \$7 in total (r1, r2, r3)' "$dir/gh.log" || fail "the comment text: $(grep 'issue comment' "$dir/gh.log")"
! grep -q '^issue comment' <(grep -v '1256' "$dir/gh.log") || fail "a theme without an issue was posted"
: >"$dir/gh.log"
"$loop" themes --post >/dev/null 2>&1
! grep -q '^issue comment' "$dir/gh.log" || fail "the same counts were posted twice: $(cat "$dir/gh.log")"
analysis r7 B 3.0 aaa111 "Another fixer edit outside the module." fixer-outside-module
sed -i 's/"r-invented"\]/"r-invented", "r7"]/' "$dir/claude"
"$loop" themes --post >/dev/null 2>&1
[[ "$(grep -c '^issue comment 1256 ' "$dir/gh.log")" == 1 ]] && grep -q 'shows in 4 run(s) that cost \$10 in total' "$dir/gh.log" || fail "changed counts must be posted again: $(cat "$dir/gh.log")"
echo "ok   post"
echo "ok   themes"
