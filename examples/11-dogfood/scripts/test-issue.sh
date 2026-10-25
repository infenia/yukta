#!/usr/bin/env bash
# Online test of `yk run issue` (the implementer, and --solo: method A) on the dev server, with a throwaway repository (a fake gradlew, a fake claude
# that also plays the implementer, a fake gh and a bare origin; fake-project.sh). Needs a running server.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
yk="$here/../yk"
fail() { echo "FAIL $*" >&2; exit 1; }

dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
"$here/fake-project.sh" "$dir"
repo="$dir/work/yukta"
mkdir -p "$dir/work"
git -C "$dir/proj" branch -M main
git clone -q --bare "$dir/proj" "$dir/origin.git"
git clone -q "$dir/origin.git" "$repo"
git -C "$repo" config user.email t@t
git -C "$repo" config user.name t
export YUKTA_REPO_ROOT="$repo" YUKTA_CLAUDE_BIN="$dir/claude" YUKTA_GH="$dir/gh" YUKTA_PROJECT_STATUS_CMD="$dir/project-status"
export YUKTA_CHECK_EXTRAS=off GIT_AUTHOR_NAME=t GIT_COMMITTER_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_EMAIL=t@t
worktree="$dir/work/yukta-issue-7"

exec_id="" session=""
# start FLAGS...: yk run issue 7 --detach; sets exec_id and session
start() {
  local out line
  out="$("$yk" run issue 7 --detach "$@" 2>&1)" || { echo "$out" >&2; fail "could not start"; }
  line="$(grep '^STARTED' <<<"$out")"
  exec_id="${line#*execution=}"; exec_id="${exec_id%% *}"
  session="${line##*session=}"
}
wait_for() {
  local n="$1"
  shift
  for ((i = 0; i < n * 2; i++)); do "$@" && return 0; sleep 0.5; done
  return 1
}
waiting_for() { "$yk" signals "${exec_id:0:8}" | grep -q " $1 "; }
result() { "$yk" result "$session" "$exec_id" 2>&1 | tail -1 || true; }
ended() { "$yk" tree "${exec_id:0:8}" | head -1 | grep -Eq " (SUCCESS|FAILURE|CANCELLED|TIMED_OUT)"; }
reset() {
  rm -rf "$worktree" "$repo/.yukta-dev/issue-7" "$dir/gh.log" "$dir/project-status.log" "$dir/claude.log" "$dir/implement-breaks" "$dir/fix-at"
  git -C "$repo" worktree prune
  git -C "$repo" branch -D issue/7 >/dev/null 2>&1 || true
  git -C "$dir/origin.git" branch -D issue/7 >/dev/null 2>&1 || true
  rm -f "$dir"/status-*
  echo Ready >"$dir/status"
}
last_status() { tail -1 "$dir/project-status.log" 2>/dev/null | cut -d' ' -f2-; }

# --- the whole way: both checkpoints, the check passes, a draft PR opens
reset
start
wait_for 90 waiting_for before-implement || fail "never waited at before-implement"
[[ -s "$repo/.yukta-dev/issue-7/brief.md" ]] || fail "no brief to edit at the checkpoint"
grep -q "Do the fake thing" "$repo/.yukta-dev/issue-7/brief.md" || fail "the brief does not hold the issue"
"$yk" approve "${exec_id:0:8}" before-implement >/dev/null
wait_for 180 waiting_for before-pr || fail "never waited at before-pr: $("$yk" tree "${exec_id:0:8}")"
"$yk" tree "${exec_id:0:8}" | grep -q "check-impl" || fail "the tree does not show the check below the run"
"$yk" approve "${exec_id:0:8}" before-pr >/dev/null
wait_for 60 ended || fail "the run did not end after the approval"
got="$(result)"
grep -q "status=SUCCESS" <<<"$got" || fail "the issue run did not succeed: $got"
grep -q "pr create.*--draft" "$dir/gh.log" || fail "no draft PR was opened: $(cat "$dir/gh.log")"
grep -q -- "--head issue/7" "$dir/gh.log" || fail "the PR is not from issue/7"
grep -q "Fixes #7" "$dir/gh.log" || fail "the PR does not say Fixes #7"
grep -q "RESULT status=SUCCESS" "$dir/gh.log" || fail "the PR has no RESULT line"
[[ "$(last_status)" == "In progress" ]] || fail "the issue is not In progress: $(cat "$dir/project-status.log")"
git -C "$dir/origin.git" rev-parse --verify -q issue/7 >/dev/null || fail "the branch was not pushed"
grep -q "session=[0-9a-f-]\{36\}" "$dir/claude.log" || fail "the implementer was not started with a session id"
echo "ok   issue to draft PR"

# --- the check fails and the fixer resumes the implementer's own session
reset
touch "$dir/implement-breaks"
echo resume >"$dir/fix-at"
start --no-pause before-implement,before-pr
wait_for 240 ended || fail "the run did not end"
got="$(result)"
grep -q "status=SUCCESS" <<<"$got" || fail "the run did not succeed after the fixer: $got"
# the check's RESULT line, with what was healed, goes into the pull request
grep -Eq "RESULT status=SUCCESS .*healed=compile:resume" "$dir/gh.log" || fail "the PR does not say the fixer healed by resuming: $(cat "$dir/gh.log")"
impl="$(grep -o 'session=[0-9a-f-]\{36\}' "$dir/claude.log" | head -1 | cut -d= -f2)"
grep -q "resume=$impl" "$dir/claude.log" || fail "the fixer did not resume the implementer's session $impl: $(cat "$dir/claude.log")"
echo "ok   the fixer resumes the implementer"

# --- not Ready: nothing is claimed, no worktree, the run ends ESCALATE
reset
echo Backlog >"$dir/status"
start --no-pause before-implement,before-pr
wait_for 60 ended || fail "the run did not end"
got="$(result)"
grep -q "status=ESCALATE" <<<"$got" || fail "a not Ready issue did not end ESCALATE: $got"
[[ ! -e "$worktree" ]] || fail "a worktree was made for an issue that is not Ready"
[[ ! -s "$dir/project-status.log" ]] || fail "an issue that is not Ready was claimed"
echo "ok   not Ready"

# --- rejecting before the PR gives the issue back and keeps the worktree
reset
start --no-pause before-implement
wait_for 180 waiting_for before-pr || fail "never waited at before-pr (reject)"
"$yk" reject "${exec_id:0:8}" before-pr >/dev/null
wait_for 60 ended || fail "the run did not end after the rejection"
got="$(result)"
grep -q "status=ESCALATE" <<<"$got" || fail "a rejected run did not end ESCALATE: $got"
[[ "$(last_status)" == Ready ]] || fail "the rejected issue is not Ready again: $(cat "$dir/project-status.log")"
[[ -e "$worktree/feature.txt" ]] || fail "the worktree was not kept"
! grep -q "pr create" "$dir/gh.log" || fail "a PR was opened although the run was rejected"
echo "ok   reject"

# --- method A (--solo): one Claude session does the issue, the check is plain (no healing), a draft PR opens
reset
start --solo --no-pause before-pr
wait_for 240 ended || fail "the solo run did not end: $("$yk" tree "${exec_id:0:8}")"
got="$(result)"
grep -q "status=SUCCESS" <<<"$got" || fail "the solo run did not succeed: $got"
grep -q "pr create.*--draft" "$dir/gh.log" || fail "no draft PR was opened by the solo run: $(cat "$dir/gh.log")"
grep -q "Fixes #7" "$dir/gh.log" || fail "the solo PR does not say Fixes #7"
grep -q "RESULT status=SUCCESS" "$dir/gh.log" || fail "the solo PR has no RESULT line"
"$yk" tree "${exec_id:0:8}" | grep -q "check-plain" || fail "the solo run was not judged by check-plain: $("$yk" tree "${exec_id:0:8}")"
[[ "$(grep -c solo-prompt "$dir/claude.log")" == 1 ]] || fail "the solo session was not started once with the solo prompt: $(cat "$dir/claude.log")"
! grep -q "session=[0-9a-f-]\{36\}" "$dir/claude.log" || fail "the solo session was started as a resumable implementer: $(cat "$dir/claude.log")"
[[ "$(jq -r .done "$repo/.yukta-dev/issue-7/solo.json")" == true && "$(jq -r .cost "$repo/.yukta-dev/issue-7/solo.json")" == 0.5 ]] || fail "solo.json: $(cat "$repo/.yukta-dev/issue-7/solo.json")"
[[ "$(jq -r .total_cost_usd "$repo/.yukta-dev/issue-7/solo.out.json")" == 0.5 ]] || fail "solo.out.json is not the session's output"
echo "ok   solo to draft PR"

# --- method A with a failing check: nothing heals it; the run ends ESCALATE, the issue is Ready again, no PR
reset
touch "$dir/implement-breaks"
start --solo --no-pause before-pr
wait_for 240 ended || fail "the failing solo run did not end"
got="$(result)"
grep -q "status=ESCALATE" <<<"$got" || fail "a failing solo run did not end ESCALATE: $got"
[[ "$(grep -c 'model=' "$dir/claude.log")" == 1 ]] || fail "something other than the solo session ran Claude: $(cat "$dir/claude.log")"
[[ "$(last_status)" == Ready ]] || fail "the issue is not Ready again: $(cat "$dir/project-status.log")"
! grep -q "pr create" "$dir/gh.log" || fail "a PR was opened although the check failed"
[[ -e "$worktree/feature.txt" ]] || fail "the worktree was not kept"
echo "ok   solo with a failing check"

# --- yk pick with only method A enabled: the draw comes first, the solo run follows, the ledger gets the session's usage
reset
export YUKTA_LOOP_DIR="$dir/loop" YUKTA_LOOP_METHODS=A YUKTA_LOOP_ANALYZE=off
"$here/loop.sh" pick 7 >"$dir/pick.out" 2>&1 || fail "pick failed: $(tail -5 "$dir/pick.out")"
unset YUKTA_LOOP_DIR YUKTA_LOOP_METHODS YUKTA_LOOP_ANALYZE
[[ "$(jq -s '[.[] | select(.type == "draw")] | length' "$dir/loop/ledger.jsonl")" == 1 ]] || fail "no single draw: $(cat "$dir/loop/ledger.jsonl")"
out="$(jq -s '[.[] | select(.type == "outcome")] | last' "$dir/loop/ledger.jsonl")"
[[ "$(jq -r .method <<<"$out")" == A && "$(jq -r .result <<<"$out")" == "RESULT status=SUCCESS"* ]] || fail "outcome: $out"
[[ "$(jq -r .cost_usd <<<"$out")" == 0.5 && "$(jq -r .sub.attempts <<<"$out")" == 0 ]] || fail "the outcome of method A is the solo session alone: $out"
[[ "$(jq -r '.main."gen_ai.usage.cache_read.input_tokens"' <<<"$out")" == 100 ]] || fail "outcome tokens: $out"
echo "ok   pick with method A"

# --- method C (--nav): the nav step indexes the worktree and the implementer gets the index
cat >"$dir/cbm" <<'SH'
#!/usr/bin/env bash
[[ "$1" == --version ]] && { echo "codebase-memory-mcp 9.9.9"; exit 0; }
echo '{"project":"fake-project","status":"indexed"}'
SH
chmod +x "$dir/cbm"
export YUKTA_NAV_BACKENDS=cbm YUKTA_NAV_CBM_BIN="$dir/cbm" YUKTA_NAV_CBM_CACHE="$dir/cbm-cache"
reset
start --nav --no-pause before-implement,before-pr
wait_for 240 ended || fail "the method C run did not end: $("$yk" tree "${exec_id:0:8}")"
got="$(result)"
grep -q "status=SUCCESS" <<<"$got" || fail "the method C run did not succeed: $got"
n="$repo/.yukta-dev/issue-7/nav.json"
[[ "$(jq -r .backend "$n")" == cbm && "$(jq -r .ok "$n")" == true ]] || fail "nav.json: $(cat "$n")"
mcp="$repo/.yukta-dev/issue-7/nav-mcp.json"
grep -q "session=[0-9a-f-]\{36\} .*mcp=$mcp" "$dir/claude.log" || fail "the implementer did not get the MCP config: $(cat "$dir/claude.log")"
grep -q nav-note "$dir/claude.log" || fail "the implementer's prompt does not name the index: $(cat "$dir/claude.log")"
"$yk" tree "${exec_id:0:8}" | grep -q "implement-issue-nav" || fail "the run did not use the nav workflow: $("$yk" tree "${exec_id:0:8}")"
echo "ok   method C"

# --- method C: a fixer that resumes the implementer gets the very same MCP config (else the cache is re-written)
reset
touch "$dir/implement-breaks"
echo resume >"$dir/fix-at"
start --nav --no-pause before-implement,before-pr
wait_for 300 ended || fail "the method C run with a failing check did not end"
got="$(result)"
grep -q "status=SUCCESS" <<<"$got" || fail "the method C run did not succeed after the fixer: $got"
[[ "$(grep -c "mcp=$mcp" "$dir/claude.log")" -ge 2 ]] || fail "the fixer did not get the implementer's MCP config: $(cat "$dir/claude.log")"
impl="$(grep -o 'session=[0-9a-f-]\{36\}' "$dir/claude.log" | head -1 | cut -d= -f2)"
grep -q "resume=$impl mcp=$mcp" "$dir/claude.log" || fail "the resumed fixer has another tool set than the implementer: $(cat "$dir/claude.log")"
echo "ok   method C fixer"

# --- a plain run does not pick up the index of an earlier method C run
reset
echo Backlog >"$dir/status"
mkdir -p "$repo/.yukta-dev/issue-7"
echo '{"ok":true,"backend":"cbm","mcp_config":"/nowhere","tools":[]}' >"$repo/.yukta-dev/issue-7/nav.json"
start --no-pause before-implement,before-pr
wait_for 60 ended || fail "the run did not end (stale nav.json)"
[[ ! -e "$repo/.yukta-dev/issue-7/nav.json" ]] || fail "a plain run left the stale nav.json of a method C run"
unset YUKTA_NAV_BACKENDS YUKTA_NAV_CBM_BIN YUKTA_NAV_CBM_CACHE
echo "ok   no stale index"
echo "ok   issue"
