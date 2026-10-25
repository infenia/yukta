#!/usr/bin/env bash
# Online test of `yk run issue` (the implementer and its fixers) on the dev server, with a throwaway repository (a fake gradlew, a fake claude
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
# no code index unless a test asks for one (the nav step would probe the real backends), and no System One triage (no Ollama)
export YUKTA_NAV_BACKENDS=none YUKTA_TRIAGE=off YUKTA_CHECK_EXTRAS=off GIT_AUTHOR_NAME=t GIT_COMMITTER_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_EMAIL=t@t
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
"$yk" tree "${exec_id:0:8}" | grep -q "check" || fail "the tree does not show the check below the run"
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

# --- the check fails and a fresh fixer session heals it (the triage is off: heal starts at sonnet, medium effort)
reset
touch "$dir/implement-breaks"
echo sonnet/medium >"$dir/fix-at"
start --no-pause before-implement,before-pr
wait_for 240 ended || fail "the run did not end"
got="$(result)"
grep -q "status=SUCCESS" <<<"$got" || fail "the run did not succeed after the fixer: $got"
# the check's RESULT line, with what was healed, goes into the pull request
grep -Eq "RESULT status=SUCCESS .*healed=compile:sonnet/medium" "$dir/gh.log" || fail "the PR does not say what the fixer healed: $(cat "$dir/gh.log")"
grep -q "model=sonnet effort=medium session=- resume=-" "$dir/claude.log" || fail "the fixer is not a fresh session of its own: $(cat "$dir/claude.log")"
! grep -q "resume=[0-9a-f]" "$dir/claude.log" || fail "a session was resumed: $(cat "$dir/claude.log")"
echo "ok   a fresh fixer heals the check"

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

# --- the nav step indexes the worktree and the implementer gets the index
cat >"$dir/cbm" <<'SH'
#!/usr/bin/env bash
[[ "$1" == --version ]] && { echo "codebase-memory-mcp 9.9.9"; exit 0; }
echo '{"project":"fake-project","status":"indexed"}'
SH
chmod +x "$dir/cbm"
export YUKTA_NAV_BACKENDS=cbm YUKTA_NAV_CBM_BIN="$dir/cbm" YUKTA_NAV_CBM_CACHE="$dir/cbm-cache"
reset
start --no-pause before-implement,before-pr
wait_for 240 ended || fail "the run did not end: $("$yk" tree "${exec_id:0:8}")"
got="$(result)"
grep -q "status=SUCCESS" <<<"$got" || fail "the run did not succeed: $got"
n="$repo/.yukta-dev/issue-7/nav.json"
[[ "$(jq -r .backend "$n")" == cbm && "$(jq -r .ok "$n")" == true ]] || fail "nav.json: $(cat "$n")"
mcp="$repo/.yukta-dev/issue-7/nav-mcp.json"
grep -q "session=[0-9a-f-]\{36\} .*mcp=$mcp" "$dir/claude.log" || fail "the implementer did not get the MCP config: $(cat "$dir/claude.log")"
grep -q nav-note "$dir/claude.log" || fail "the implementer's prompt does not name the index: $(cat "$dir/claude.log")"
"$yk" tree "${exec_id:0:8}" | grep -q "implement-issue" || fail "the run did not use the issue workflow: $("$yk" tree "${exec_id:0:8}")"
echo "ok   nav index"

# --- a fresh fixer gets the very same MCP config and index as the implementer
reset
touch "$dir/implement-breaks"
echo sonnet/medium >"$dir/fix-at"
start --no-pause before-implement,before-pr
wait_for 300 ended || fail "the run with a failing check did not end"
got="$(result)"
grep -q "status=SUCCESS" <<<"$got" || fail "the run did not succeed after the fixer: $got"
[[ "$(grep -c "mcp=$mcp" "$dir/claude.log")" -ge 2 ]] || fail "the fixer did not get the implementer's MCP config: $(cat "$dir/claude.log")"
grep -q "model=sonnet effort=medium session=- resume=- mcp=$mcp" "$dir/claude.log" || fail "the fixer is not a fresh session with the index: $(cat "$dir/claude.log")"
unset YUKTA_NAV_BACKENDS YUKTA_NAV_CBM_BIN YUKTA_NAV_CBM_CACHE
echo "ok   nav fixer"

echo "ok   issue"
