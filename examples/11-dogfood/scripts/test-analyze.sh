#!/usr/bin/env bash
# Offline test of the analyzer (analyze.sh, analyze-facts.jq, analyzer-known.json): a finished run is built from fixtures
# (ledger, heal records, a git worktree, session transcripts), a fake gh and a fake claude play GitHub and the judge.
# No server, no network, no spend.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
mkdir -p "$dir/bin" "$dir/work"
export YUKTA_LOOP_DIR="$dir/loop" YUKTA_REPO_ROOT="$dir/work/yukta" CLAUDE_CONFIG_DIR="$dir/claude-config" \
  YUKTA_GH="$dir/bin/gh" YUKTA_CLAUDE_BIN="$dir/claude" PATH="$dir/bin:$PATH" YUKTA_LOOP_SEED=s
repo="$dir/work/yukta" wt="$dir/work/yukta-issue-9"
analyze="$here/analyze.sh"

# ---- fixtures: a repository with origin/main and an issue worktree whose branch changes cli/ and core/
git init -q --bare "$dir/origin.git"
git clone -q "$dir/origin.git" "$repo" 2>/dev/null
git -C "$repo" config user.email t@t; git -C "$repo" config user.name t
mkdir -p "$repo/cli" "$repo/core"; echo base >"$repo/cli/a.go"; echo base >"$repo/core/X.java"
git -C "$repo" add -A; git -C "$repo" commit -q -m base; git -C "$repo" branch -M main; git -C "$repo" push -q origin main 2>/dev/null
git -C "$repo" worktree add -q -b issue/9 "$wt" origin/main
echo change >"$wt/cli/new.go"; echo change >"$wt/core/Y.java"; git -C "$wt" add -A; git -C "$wt" commit -q -m "fix: the issue"
mkdir -p "$wt/.yukta-dev/heal/check" "$repo/.yukta-dev/issue-9"
echo '{"stage":"tests","attempt":1,"mode":"resume","fixed":false,"files":[],"cost":0.9,"summary":"not caused by this branch","usage":{"input":28,"cache_creation":51351,"cache_read":433611,"output":3319}}' >"$wt/.yukta-dev/heal/check/tests-1.json"
echo '{"stage":"tests","attempt":2,"mode":"resume","fixed":true,"files":["core/Y.java"],"cost":1.2,"summary":"edited a test","usage":{"input":22,"cache_creation":14066,"cache_read":651907,"output":5274}}' >"$wt/.yukta-dev/heal/check/tests-2.json"
echo '{"cost":0.3,"done":true,"summary":"x","usage":{"input_tokens":20,"cache_creation_input_tokens":26354,"cache_read_input_tokens":186780,"output_tokens":4563}}' >"$repo/.yukta-dev/issue-9/implement.json"

# a transcript with a turn that re-wrote context: write 17000 read 6400 right after a turn whose context was 20000
transcript() { # FILE T0-SECONDS [SCALE]: SCALE multiplies the token counts
  python3 -I - "$1" "$2" "${3:-1}" <<'EOF'
import json, sys
path, t0, scale = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
rows = [(0, 8000, 6400, "Read"), (3, 1000, 14400, "Bash"), (6, 800, 15400, "Bash"), (9, 700, 16200, "Bash"), (200, 17000, 6400, None)]
rows = [(a, w * scale, r * scale, t) for a, w, r, t in rows]
with open(path, "w") as f:
    for i, (at, w, r, tool) in enumerate(rows):
        ts = "2026-10-25T10:%02d:%02dZ" % ((t0 + at) // 60 % 60, (t0 + at) % 60)
        content = [{"type": "tool_use", "id": "t%s%d" % (sys.argv[2], i), "name": tool, "input": {}}] if tool else []
        f.write(json.dumps({"type": "assistant", "timestamp": ts, "message": {"id": "m%s%d" % (sys.argv[2], i), "model": "claude-opus-5-5",
                "content": content, "usage": {"input_tokens": 2, "cache_creation_input_tokens": w, "cache_read_input_tokens": r, "output_tokens": 100 + i}}}) + "\n")
EOF
}
tdir="$CLAUDE_CONFIG_DIR/projects/$(python3 -I -c 'import os,re,sys;print(re.sub(r"[^A-Za-z0-9]", "-", os.path.realpath(sys.argv[1])))' "$wt")"
mkdir -p "$tdir"
transcript "$tdir/aaaa1111.jsonl" 0

# the ledger: a draw and an outcome of method B whose RESULT line says healed=-
mkdir -p "$YUKTA_LOOP_DIR"
id=1700000000-9
jq -nc --arg id "$id" '{type: "draw", id: $id, issue: 9, size: "S", method: "B", loop_changing: false, loop_sha: "abc123", regime: "A,B"}' >"$YUKTA_LOOP_DIR/ledger.jsonl"
jq -nc --arg id "$id" '{type: "outcome", draw: $id, issue: 9, method: "B", result: "RESULT status=SUCCESS failed_node=- healed=- execution=e session=s", wall_s: 600, cost_usd: 2.4,
  main: {cost_usd: 0.3}, sub: {cost_usd: 2.1}, analyzer_cost_usd: 0}' >>"$YUKTA_LOOP_DIR/ledger.jsonl"

# ---- a fake gh: the issue names the cli module; #1234, #1248, #1235 and #1256 are open (list in $dir/closed for closed ones)
cat >"$dir/bin/gh" <<'SH'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")/.." && pwd)"
echo "$*" >>"$here/gh.log"
case "$1 $2" in
  "issue view")
    if grep -q -- '--json state' <<<"$*"; then grep -qx "$3" "$here/closed" 2>/dev/null && echo CLOSED || echo OPEN
    else jq -nc '{number: 9, title: "Fix the cli problem", body: "Change cli so it prints more.", labels: [], state: "OPEN", assignees: [{login: "tester"}]}'; fi ;;
  "api user") echo tester ;;
  "project item-list") echo Ready ;;
  "issue comment") ;;
  "issue list") echo '[]' ;;
  "issue create") echo "https://github.com/infenia/yukta/issues/9001" ;;
  "api -X") ;;
  "api repos/infenia/yukta/issues/1218/sub_issues")
    printf '#1232 Dev-server environment leaks into the Gradle test JVMs\n#1234 A fixer in an issue run can change files outside the issue\n#1256 Fixers in an issue run resume the implementer on Opus\n' ;;
  "api repos/infenia/yukta/issues/9001") echo 42 ;;
  *) echo "fake gh: unsupported: $*" >&2; exit 1 ;;
esac
SH
chmod +x "$dir/bin/gh"
# ---- a fake claude as the judge: one new proposal and one that duplicates an open issue
cat >"$dir/claude" <<'SH'
#!/usr/bin/env bash
echo "judge called" >>"$(dirname "$0")/claude.log"
[[ -e "$(dirname "$0")/judge-fails" ]] && exit 1
jq -nc '{total_cost_usd: 0.12, structured_output: {note: "Two fixer attempts spent $2.10 on failures in core that the change in cli does not touch.",
  proposals: [{title: "The issue run never checks that a fixer stays inside the module", evidence: "fixer attempt 2 changed core/Y.java", expected: "a guard", priority: "P2", module: "build", kind: "improvement", scope: "dogfood", duplicate_of: null},
              {title: "Stop fixers outside the module", evidence: "same as #1234", expected: "a guard", priority: "P2", module: "build", kind: "bug", scope: "dogfood", duplicate_of: 1234}]}}'
SH
chmod +x "$dir/claude"
rules() { jq -r '.findings[].rule' "$YUKTA_LOOP_DIR/analysis/$id.json" | sort | paste -sd' '; }

# ---- stage 1: the facts and the rule findings
"$analyze" "$id" --dry-run --no-judge >"$dir/out1" 2>&1 || fail "analyze failed: $(cat "$dir/out1")"
a="$YUKTA_LOOP_DIR/analysis/$id.json"
[[ "$(jq -r '.facts.fixer_attempts' "$a")" == 2 && "$(jq -r '.facts.noop_attempts' "$a")" == 1 ]] || fail "attempts: $(jq -c .facts "$a")"
[[ "$(jq -r '.facts.noop_cost_usd' "$a")" == 0.9 && "$(jq -r '.facts.fixer_cost_usd' "$a")" == 2.1 ]] || fail "costs: $(jq -c .facts "$a")"
[[ "$(jq -c '.facts.outside_files' "$a")" == '["core/Y.java"]' ]] || fail "outside files: $(jq -c .facts.outside_files "$a")"
[[ "$(jq -c '.facts.modules' "$a")" == '["cli"]' ]] || fail "modules: $(jq -c .facts.modules "$a")"
[[ "$(jq -c '.facts.files_changed' "$a")" == '["cli/new.go","core/Y.java"]' ]] || fail "files changed: $(jq -c .facts.files_changed "$a")"
[[ "$(jq -r '.facts.rewrite_turns' "$a")" == 1 && "$(jq -r '.facts.sessions' "$a")" == 1 ]] || fail "cache trace: $(jq -c .facts "$a")"
[[ "$(jq -c '.facts.tool_use' "$a")" == '{"Bash":3,"Read":1}' ]] || fail "tool use: $(jq -c .facts.tool_use "$a")"
[[ "$(jq -r '.facts.healed_field' "$a")" == "-" && "$(jq -r '.facts.status' "$a")" == SUCCESS ]] || fail "result fields"
[[ "$(rules)" == "cache-rewrite-on-resume fixer-outside-module healed-field-mismatch noop-fixers" ]] || fail "rules: $(rules)"
echo "ok   facts and rules"

# ---- dry run: names what it would do and writes nothing to GitHub
: >"$dir/gh.log"
"$analyze" "$id" --dry-run >"$dir/out2" 2>&1 || fail "dry run failed: $(cat "$dir/out2")"
grep -q 'WOULD COMMENT #1234' "$dir/out2" || fail "no comment on 1234: $(cat "$dir/out2")"
grep -q 'WOULD COMMENT #1235' "$dir/out2" && grep -q 'WOULD COMMENT #1256' "$dir/out2" && grep -q 'WOULD COMMENT #1248' "$dir/out2" || fail "known issues: $(cat "$dir/out2")"
grep -q 'WOULD FILE: The issue run never checks that a fixer stays inside the module' "$dir/out2" || fail "no new issue proposed: $(cat "$dir/out2")"
[[ "$(grep -c 'WOULD COMMENT #1234' "$dir/out2")" == 2 ]] || fail "the duplicate proposal should comment on #1234 too: $(cat "$dir/out2")"
! grep -qE '^(issue create|issue comment|api -X)' "$dir/gh.log" || fail "a dry run wrote to GitHub: $(cat "$dir/gh.log")"
[[ "$(jq -r .dry_run "$a")" == true && "$(jq -r .analyzer_cost_usd "$a")" == 0.12 ]] || fail "analysis record: $(jq -c '{dry_run, analyzer_cost_usd}' "$a")"
echo "ok   dry run"

# ---- for real: comments on what is known, files one new issue, attaches it under the epic, the judge's cost is on the ledger
: >"$dir/gh.log"
before="$("$here/loop.sh" total)"
"$analyze" "$id" >"$dir/out3" 2>&1 || fail "analyze failed: $(cat "$dir/out3")"
for n in 1234 1235 1248 1256; do grep -q "issue comment $n " "$dir/gh.log" || fail "no comment on #$n: $(cat "$dir/gh.log")"; done
[[ "$(grep -c 'issue create' "$dir/gh.log")" == 1 ]] || fail "one new issue expected: $(grep 'issue create' "$dir/gh.log")"
grep -q 'sub_issues -F sub_issue_id=42' "$dir/gh.log" || fail "the new issue was not attached under the epic: $(cat "$dir/gh.log")"
grep -q 'ATTACHED #9001 to #1218' "$dir/out3" || fail "output: $(cat "$dir/out3")"
grep -q 'analyzer, run 1700000000-9' "$dir/gh.log" || fail "the evidence does not name the run"
[[ "$("$here/loop.sh" total)" != "$before" ]] || fail "the analyzer's cost is not in the loop's total"
[[ "$(jq -s '[.[] | select(.type == "analysis")] | length' "$YUKTA_LOOP_DIR/ledger.jsonl")" == 3 ]] || fail "analysis records: $(cat "$YUKTA_LOOP_DIR/ledger.jsonl")"
echo "ok   filing"

# ---- a closed known issue: the rule files a new issue with its own title instead of commenting
: >"$dir/gh.log"; echo 1235 >"$dir/closed"
"$analyze" "$id" --no-judge >"$dir/out4" 2>&1 || fail "analyze failed: $(cat "$dir/out4")"
! grep -q 'issue comment 1235 ' "$dir/gh.log" || fail "commented on a closed issue"
grep -q 'RESULT of an issue run says healed=- although its fixers made changes' "$dir/gh.log" || fail "the closed rule was not filed again: $(cat "$dir/gh.log")"
grep -q 'are closed' "$dir/gh.log" || fail "the new issue does not say the old one is closed"
rm -f "$dir/closed"
echo "ok   closed known issue"

# ---- at most YUKTA_ANALYZER_MAX_FILED new issues per run
: >"$dir/gh.log"; echo "1234 1235 1248 1256" | tr ' ' '\n' >"$dir/closed"
YUKTA_ANALYZER_MAX_FILED=2 "$analyze" "$id" --no-judge >"$dir/out5" 2>&1 || fail "analyze failed: $(cat "$dir/out5")"
[[ "$(grep -c 'issue create' "$dir/gh.log")" == 2 ]] || fail "the cap of 2 new issues was not kept: $(grep 'issue create' "$dir/gh.log")"
grep -q 'SKIPPED (at most 2 new issues per run)' "$dir/out5" || fail "no note about the cap: $(cat "$dir/out5")"
rm -f "$dir/closed"
echo "ok   cap"

# ---- a second, large session in the worktree is a restart and shows tokens the records do not hold; a judge that fails leaves the rules intact
transcript "$tdir/bbbb2222.jsonl" 300 40
touch "$dir/judge-fails"
"$analyze" "$id" --dry-run >"$dir/out6" 2>&1 || fail "a failing judge broke the analysis: $(cat "$dir/out6")"
rm -f "$dir/judge-fails"
[[ "$(rules)" == *restart-overwrites-records* && "$(rules)" == *records-undercount* ]] || fail "no restart or undercount finding: $(rules)"
[[ "$(jq -r .analyzer_cost_usd "$a")" == 0 && "$(jq '.proposals | length' "$a")" == 0 ]] || fail "failing judge: $(jq -c '{analyzer_cost_usd, proposals}' "$a")"
echo "ok   restart, failing judge"

# ---- pick runs the analyzer after the outcome (YUKTA_LOOP_ANALYZE=dry here: it names what it would do and writes nothing to GitHub)
cat >"$dir/runner" <<'SH'
#!/usr/bin/env bash
echo '{"result":"ok","total_cost_usd":1,"num_turns":2,"duration_ms":1,"usage":{"input_tokens":1,"cache_creation_input_tokens":1,"cache_read_input_tokens":1,"output_tokens":1}}' >"$3/main.json"
echo "RESULT status=SUCCESS failed_node=- healed=- execution=e session=s"
SH
chmod +x "$dir/runner"
: >"$dir/gh.log"
YUKTA_LOOP_METHODS=B YUKTA_LOOP_RUNNER="$dir/runner" YUKTA_LOOP_ANALYZE=dry "$here/loop.sh" pick 10 >"$dir/out8" 2>&1 || fail "pick failed: $(cat "$dir/out8")"
grep -q '^ANALYSIS ' "$dir/out8" || fail "pick did not run the analyzer: $(cat "$dir/out8")"
! grep -qE '^(issue create|issue comment|api -X)' "$dir/gh.log" || fail "the analyzer wrote to GitHub in dry mode: $(cat "$dir/gh.log")"
[[ "$(jq -s '[.[] | select(.type == "analysis" and .issue == 10)] | length' "$YUKTA_LOOP_DIR/ledger.jsonl")" == 1 ]] || fail "no analysis record for the picked issue"
before="$(jq -s '[.[] | select(.type == "analysis")] | length' "$YUKTA_LOOP_DIR/ledger.jsonl")"
YUKTA_LOOP_METHODS=B YUKTA_LOOP_RUNNER="$dir/runner" YUKTA_LOOP_ANALYZE=off "$here/loop.sh" pick 11 >/dev/null 2>&1 || fail "pick with the analyzer off failed"
[[ "$(jq -s '[.[] | select(.type == "analysis")] | length' "$YUKTA_LOOP_DIR/ledger.jsonl")" == "$before" ]] || fail "YUKTA_LOOP_ANALYZE=off still analyzed"
echo "ok   pick runs the analyzer"

# ---- unknown draw
"$analyze" nope >"$dir/out7" 2>&1 && fail "an unknown draw was analyzed"
grep -q 'no draw nope' "$dir/out7" || fail "message: $(cat "$dir/out7")"
echo "ok   analyzer"
