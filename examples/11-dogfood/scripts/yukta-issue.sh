#!/usr/bin/env bash
# The steps of the implement-issue workflow (see issue_workflows in _lib.sh), each run by a Yukta
# PROCESS_EXECUTOR node, usable by hand with the environment below.
#
#   yukta-issue.sh claim      the issue is open, assigned to you and Ready: move it to In progress (exit 3 otherwise)
#   yukta-issue.sh worktree   a git worktree on a new branch issue/<n> from origin/main
#   yukta-issue.sh brief      write the brief the implementer reads (T0 may edit it before the work starts)
#   yukta-issue.sh implement  a headless Claude implements the brief in the worktree (opus, $YUKTA_IMPL_BUDGET)
#   yukta-issue.sh pr         commit, push and open a DRAFT pull request with Fixes #<n> and the check's RESULT line
#   yukta-issue.sh release    give a claimed issue back (Ready); the worktree stays for whoever picks it up
#
# Environment: YUKTA_ISSUE (number), YUKTA_REPO_DIR (the main checkout), YUKTA_WORKTREE, YUKTA_IMPL_SESSION (a uuid),
# YUKTA_CHECK_EXECUTION (pr: the execution of the check), YUKTA_GH_REPO (default infenia/yukta), YUKTA_GH (gh),
# YUKTA_IMPL_MODEL (opus), YUKTA_IMPL_BUDGET (USD, 5), YUKTA_PROJECT_STATUS_CMD (tests: replaces the project update).
# State per issue: <repo>/.yukta-dev/issue-<n>/ (claimed, brief.md, implement.json, implement.out).
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
action="${1:?usage: yukta-issue.sh claim|worktree|brief|implement|pr|release}"
n="${YUKTA_ISSUE:?YUKTA_ISSUE is required}"
repo="${YUKTA_REPO_DIR:?YUKTA_REPO_DIR is required}"
wt="${YUKTA_WORKTREE:-$repo/../yukta-issue-$n}"
gh="${YUKTA_GH:-gh}"
gh_repo="${YUKTA_GH_REPO:-infenia/yukta}"
state="$repo/.yukta-dev/issue-$n"
mkdir -p "$state"

nope() { echo "$*" >&2; exit 3; }

# set_status URL STATUS: moves the issue on the Yukta Roadmap project
set_status() {
  if [[ -n "${YUKTA_PROJECT_STATUS_CMD:-}" ]]; then "$YUKTA_PROJECT_STATUS_CMD" "$1" "$2"; return; fi
  # shellcheck source=/dev/null
  . "$repo/.github/scripts/project-lib.sh"
  PROJECT_OWNER=infenia PROJECT_NUMBER=5 PROJECT_TOKEN="$("$gh" auth token)" project_add_item "$1" Status="$2"
}

case "$action" in
  claim)
    login="$("$gh" api user -q .login)"
    info="$("$gh" issue view "$n" -R "$gh_repo" --json number,title,url,state,assignees)" || nope "issue #$n not found"
    [[ "$(jq -r .state <<<"$info")" == OPEN ]] || nope "issue #$n is not open"
    jq -e --arg l "$login" '.assignees | any(.login == $l)' <<<"$info" >/dev/null || nope "issue #$n is not assigned to $login"
    status="$("$gh" project item-list 5 --owner infenia --limit 700 --format json -q ".items[] | select(.content.number == $n) | .status")"
    [[ "$status" == Ready ]] || nope "issue #$n is ${status:-not on the project}, not Ready: it has no acceptance criteria yet"
    url="$(jq -r .url <<<"$info")"
    set_status "$url" "In progress" || { echo "could not move #$n to In progress (gh auth refresh -s project?)" >&2; exit 1; }
    echo "$url" >"$state/claimed"
    echo "claimed #$n: $(jq -r .title <<<"$info")"
    ;;
  worktree)
    git -C "$repo" fetch -q origin main || echo "warning: fetch failed; using the local origin/main" >&2
    if [[ -e "$wt/.git" ]]; then
      echo "reusing the worktree $wt"
    elif git -C "$repo" show-ref -q --verify "refs/heads/issue/$n"; then
      git -C "$repo" worktree add -q "$wt" "issue/$n" || exit 1
    else
      git -C "$repo" worktree add -q "$wt" -b "issue/$n" origin/main || exit 1
    fi
    # the ui build needs the node/pnpm toolchain Gradle downloaded into ui/.gradle: reuse this checkout's copy
    if [[ -d "$repo/ui/.gradle/pnpm" && ! -d "$wt/ui/.gradle/pnpm" ]]; then
      mkdir -p "$wt/ui/.gradle"
      for d in "$repo"/ui/.gradle/*; do cp -r --reflink=auto "$d" "$wt/ui/.gradle/"; done
    fi
    echo "worktree $wt on issue/$n"
    ;;
  brief)
    info="$("$gh" issue view "$n" -R "$gh_repo" --json number,title,url,body)" || exit 1
    {
      jq -r '"# Issue #\(.number): \(.title)\n\n\(.url)\n\n\(.body)"' <<<"$info"
      cat <<TXT

---
You are working in the git worktree $wt on branch issue/$n. Implement the issue and its acceptance criteria above,
add tests, verify with the smallest Gradle scope that covers the change, and write .yukta-dev/pr.md (title, then
description). Answer with the JSON object only.
TXT
    } >"$state/brief.md"
    echo "brief: $state/brief.md"
    ;;
  implement)
    # shellcheck source=_claude.sh
    source "$here/_claude.sh"
    : "${YUKTA_IMPL_SESSION:?YUKTA_IMPL_SESSION is required}"
    export YK_SCHEMA='{"type":"object","properties":{"done":{"type":"boolean"},"summary":{"type":"string"}},"required":["done","summary"]}'
    cd "$wt" || exit 1
    out="$state/implement.out"
    : >"$out"
    result="$(yk_claude "${YUKTA_IMPL_MODEL:-opus}" "$(cat "$state/brief.md")" "$here/../implementer-rules.md" \
      "${YUKTA_IMPL_BUDGET:-5}" "$out" "$YUKTA_IMPL_SESSION")"
    answer="$(jq -c '.structured_output // (.result | fromjson? ) // {}' <<<"$result" 2>/dev/null || echo '{}')"
    jq -n --argjson r "${result:-null}" --argjson a "$answer" \
      '{cost: ($r.total_cost_usd // 0), usage: ($r.usage // null), done: ($a.done // false), summary: ($a.summary // "")}' \
      >"$state/implement.json" 2>/dev/null || echo '{}' >"$state/implement.json"
    echo "IMPLEMENT #$n done=$(jq -r .done "$state/implement.json") cost=\$$(jq -r .cost "$state/implement.json") summary=$(jq -r '.summary[0:200]' "$state/implement.json")"
    [[ "$(jq -r .done "$state/implement.json")" == true ]] || exit 1
    [[ -n "$(git status --porcelain)" ]] || { echo "the implementer reported done but changed nothing" >&2; exit 1; }
    ;;
  pr)
    cd "$wt" || exit 1
    title="$(head -1 .yukta-dev/pr.md 2>/dev/null | sed 's/^#* *//')"
    title="${title:-feat: $("$gh" issue view "$n" -R "$gh_repo" --json title -q .title)}"
    body="$(tail -n +2 .yukta-dev/pr.md 2>/dev/null)"
    result_line=""
    if [[ -n "${YUKTA_CHECK_EXECUTION:-}" ]]; then
      result_line="$(YUKTA_HEAL_RECORDS="$wt/.yukta-dev/heal/check" "$here/../yk" result "${YUKTA_CHECK_SESSION:-yukta-dev}" "$YUKTA_CHECK_EXECUTION" 2>/dev/null | tail -1)"
    fi
    git add -A
    git -c user.name="${GIT_AUTHOR_NAME:-$(git config user.name)}" commit -q -m "$title

Co-Authored-By: Claude <noreply@anthropic.com>" || { echo "nothing to commit" >&2; exit 1; }
    git push -q -u origin "issue/$n" || exit 1
    url="$("$gh" pr create -R "$gh_repo" --draft --base main --head "issue/$n" --title "$title" --body "$body

Fixes #$n

## Verification
${result_line:-the check ran in the workflow}

🤖 Generated with [Claude Code](https://claude.com/claude-code)")" || exit 1
    echo "PR $url"
    ;;
  release)
    if [[ -s "$state/claimed" ]]; then
      set_status "$(cat "$state/claimed")" Ready && rm -f "$state/claimed"
      echo "released #$n (Ready); the worktree stays at $wt"
    else
      echo "#$n was not claimed by this run; nothing to release"
    fi
    ;;
  *) echo "unknown action '$action'" >&2; exit 2 ;;
esac
