#!/usr/bin/env bash
# Online test of `yk run epic` and the budgets on the dev server, with a throwaway repository (fake gradlew,
# claude, gh and a bare origin; fake-project.sh). Needs a running server built from this branch.
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
export YUKTA_WARM_DELAY=2 YUKTA_CHECK_EXTRAS=off GIT_AUTHOR_NAME=t GIT_COMMITTER_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_EMAIL=t@t

exec_id="" session=""
# start KIND N FLAGS...: yk run KIND N --detach; sets exec_id and session
start() {
  local out line kind="$1" n="$2"
  shift 2
  out="$("$yk" run "$kind" "$n" --detach "$@" 2>&1)" || { echo "$out" >&2; fail "could not start"; }
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
  for n in 7 8 9; do
    rm -rf "$dir/work/yukta-issue-$n" "$repo/.yukta-dev/issue-$n"
    git -C "$repo" branch -D "issue/$n" >/dev/null 2>&1 || true
    git -C "$dir/origin.git" branch -D "issue/$n" >/dev/null 2>&1 || true
  done
  rm -rf "$repo/.yukta-dev/epic-9"
  git -C "$repo" worktree prune
  rm -f "$dir/gh.log" "$dir/project-status.log" "$dir/claude.log" "$dir/implement-breaks" "$dir/fix-at" "$dir/implement-sleep" \
    "$dir"/status-* "$dir/sub-issues"
  echo Ready >"$dir/status"
}
prs() { grep -c "pr create" "$dir/gh.log" 2>/dev/null || true; }

# --- two Ready sub-issues run at the same time, each in its own worktree, each with its own draft PR
reset
echo 4 >"$dir/implement-sleep"
start epic 9 --no-pause before-implement,before-pr,between-issues
wait_for 240 ended || fail "the epic did not end: $("$yk" tree "${exec_id:0:8}")"
got="$(result)"
grep -q "status=SUCCESS" <<<"$got" || fail "the epic did not succeed: $got"
[[ "$(prs)" == 2 ]] || fail "expected 2 draft PRs, got $(prs): $(cat "$dir/gh.log")"
grep -q -- "--head issue/7" "$dir/gh.log" && grep -q -- "--head issue/8" "$dir/gh.log" || fail "the PRs are not from issue/7 and issue/8"
# the implementers overlapped: the second started before the first one's four seconds were over
mapfile -t starts < <(grep "model=opus" "$dir/claude.log" | cut -d' ' -f1 | sort -n)
((${#starts[@]} == 2)) || fail "expected 2 implementers: $(cat "$dir/claude.log")"
awk -v a="${starts[0]}" -v b="${starts[1]}" 'BEGIN { exit !(b - a < 3.5) }' || fail "the issues did not run at the same time (${starts[*]})"
# ...but the second implementer started YUKTA_WARM_DELAY seconds after the first, to find its prompt cached
awk -v a="${starts[0]}" -v b="${starts[1]}" 'BEGIN { exit !(b - a >= 1.8) }' || fail "the implementers started together, not spread (${starts[*]})"
[[ -e "$dir/work/yukta-issue-7/feature.txt" && -e "$dir/work/yukta-issue-8/feature.txt" ]] || fail "a worktree is missing"
"$yk" tree "${exec_id:0:8}" | grep -c "run-issue" | grep -q 2 || fail "the tree does not show both issue runs"
echo "ok   two issues at once"

# --- only the Ready ones run
reset
echo Backlog >"$dir/status-8"
start epic 9 --no-pause before-implement,before-pr,between-issues
wait_for 240 ended || fail "the epic did not end (one Ready)"
got="$(result)"
grep -q "status=SUCCESS" <<<"$got" || fail "the epic did not succeed with one Ready issue: $got"
[[ "$(prs)" == 1 ]] || fail "expected 1 draft PR, got $(prs)"
[[ ! -e "$dir/work/yukta-issue-8" ]] || fail "an issue that is not Ready got a worktree"
echo "ok   only Ready issues"

# --- both issues give up: the epic ends ESCALATE, pauses once, and each issue is Ready again
reset
touch "$dir/implement-breaks"
start epic 9 --no-pause before-implement,before-pr,between-issues
# the second escalation pauses the epic: it stays RUNNING, paused, until whoever steers goes on
paused() { "$yk" tree "${exec_id:0:8}" | head -1 | grep -q "RUNNING paused"; }
wait_for 300 paused || fail "two escalations did not pause the epic: $("$yk" tree "${exec_id:0:8}")"
"$yk" resume "${exec_id:0:8}" --tree >/dev/null
wait_for 120 ended || fail "the epic did not end after it was resumed: $("$yk" tree "${exec_id:0:8}")"
got="$(result)"
grep -q "status=ESCALATE" <<<"$got" || fail "a failing epic did not end ESCALATE: $got"
[[ "$(prs)" == 0 ]] || fail "a PR was opened for an issue whose check failed"
grep -q "count=2" "$repo/.yukta-dev/epic-9/epic-pause.log" || fail "two escalations did not trigger the pause: $(cat "$repo/.yukta-dev/epic-9/epic-pause.log" 2>&1)"
for n in 7 8; do
  tail -1 <(grep "issues/$n " "$dir/project-status.log") | grep -q Ready || fail "issue $n is not Ready again: $(cat "$dir/project-status.log")"
done
echo "ok   failing issues"

# --- between-issues: each issue starts when you approve it
reset
start epic 9 --no-pause before-implement,before-pr
wait_for 90 waiting_for between-issues || fail "never waited at between-issues"
"$yk" approve "${exec_id:0:8}" between-issues >/dev/null
sleep 8
wait_for 90 waiting_for between-issues || fail "did not wait for the second issue at between-issues: $("$yk" tree "${exec_id:0:8}")"
"$yk" approve "${exec_id:0:8}" between-issues >/dev/null
wait_for 240 ended || fail "the epic did not end after both approvals"
got="$(result)"
grep -q "status=SUCCESS" <<<"$got" || fail "the gated epic did not succeed: $got"
[[ "$(prs)" == 2 ]] || fail "expected 2 draft PRs after the approvals, got $(prs)"
echo "ok   between issues"

# --- the issue budget: the fixer does not spend on an issue that is over it
reset
echo 7 >"$dir/sub-issues"
touch "$dir/implement-breaks"
echo resume >"$dir/fix-at"
YUKTA_BUDGET_ISSUE=0.4 start epic 9 --no-pause before-implement,before-pr,between-issues
wait_for 240 ended || fail "the epic did not end (budget)"
got="$(result)"
grep -q "status=ESCALATE" <<<"$got" || fail "an issue over its budget did not end ESCALATE: $got"
! grep -q "resume=[0-9a-f]\{8\}" "$dir/claude.log" || fail "a fixer ran although the issue was over its budget"
echo "ok   budget"
echo "ok   epic"
