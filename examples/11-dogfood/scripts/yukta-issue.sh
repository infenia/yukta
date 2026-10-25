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
#   yukta-issue.sh epic-list  the open, assigned, Ready sub-issues of an epic, as JSON for the epic workflow to fan out
#   yukta-issue.sh epic-pause two issue runs of the epic ended ESCALATE: pause the epic and the runs below it
#
# Environment: YUKTA_ISSUE (number) or, for the epic steps, YUKTA_EPIC and YUKTA_BAD_COUNT; YUKTA_REPO_DIR (the main checkout), YUKTA_WORKTREE, YUKTA_IMPL_SESSION (a uuid),
# YUKTA_CHECK_EXECUTION (pr: the execution of the check), YUKTA_GH_REPO (default infenia/yukta), YUKTA_GH (gh),
# YUKTA_IMPL_MODEL (opus), YUKTA_IMPL_BUDGET (USD, 5), YUKTA_PROJECT_STATUS_CMD (tests: replaces the project update).
# YUKTA_WARM_DELAY (seconds, 20) spreads the starts of the implementers.
# Budgets (USD, optional): YUKTA_BUDGET_ISSUE caps what one issue spends (the implementer and its fixers), YUKTA_BUDGET_EPIC
# what all issues of the epic do; implement refuses to start over either.
# State per issue: <repo>/.yukta-dev/issue-<n>/ (claimed, brief.md, implement.json, implement.out).
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
action="${1:?usage: yukta-issue.sh claim|worktree|brief|implement|pr|release|epic-list|epic-pause}"
repo="${YUKTA_REPO_DIR:?YUKTA_REPO_DIR is required}"
gh="${YUKTA_GH:-gh}"
gh_repo="${YUKTA_GH_REPO:-infenia/yukta}"
# git_locked CMD...: parallel issue runs of an epic share one .git/config; git fails when two writes meet
git_locked() { flock "$(git -C "$repo" rev-parse --path-format=absolute --git-common-dir)/yukta-issue.lock" "$@"; }
if [[ "$action" == epic-* ]]; then
  n="" wt="" state="$repo/.yukta-dev/epic-${YUKTA_EPIC:?YUKTA_EPIC is required}"
else
  n="${YUKTA_ISSUE:?YUKTA_ISSUE is required}"
  wt="${YUKTA_WORKTREE:-$repo/../yukta-issue-$n}"
  state="$repo/.yukta-dev/issue-$n"
fi
mkdir -p "$state"

nope() { echo "$*" >&2; exit 3; }

# set_status URL STATUS: moves the issue on the Yukta Roadmap project
set_status() {
  if [[ -n "${YUKTA_PROJECT_STATUS_CMD:-}" ]]; then "$YUKTA_PROJECT_STATUS_CMD" "$1" "$2"; return; fi
  # shellcheck source=/dev/null
  . "$repo/.github/scripts/project-lib.sh"
  PROJECT_OWNER=infenia PROJECT_NUMBER=5 PROJECT_TOKEN="$("$gh" auth token)" project_add_item "$1" Status="$2"
}

# cost_in FILE...: the dollars the records and result files add up to
cost_in() { cat "$@" 2>/dev/null | jq -s '[.[] | .cost // 0] | add // 0' ; }

# over BUDGET SPENT: true when a budget is set and spent has reached it
over() { [[ -n "$1" ]] && awk -v b="$1" -v s="$2" 'BEGIN { exit !(s + 0 >= b + 0) }'; }

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
      git_locked git -C "$repo" worktree add -q "$wt" "issue/$n" || exit 1
    else
      git_locked git -C "$repo" worktree add -q --no-track "$wt" -b "issue/$n" origin/main || exit 1
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
    spent="$(cost_in "$state/implement.json" "$wt"/.yukta-dev/heal/check/*-[0-9]*.json)"
    if over "${YUKTA_BUDGET_ISSUE:-}" "$spent"; then nope "issue #$n has spent \$$spent, its budget is \$$YUKTA_BUDGET_ISSUE (YUKTA_BUDGET_ISSUE)"; fi
    epic_spent="$(cost_in "$repo"/.yukta-dev/issue-*/implement.json "$repo"/../yukta-issue-*/.yukta-dev/heal/check/*-[0-9]*.json)"
    if over "${YUKTA_BUDGET_EPIC:-}" "$epic_spent"; then nope "the issues have spent \$$epic_spent, the budget of the epic is \$$YUKTA_BUDGET_EPIC (YUKTA_BUDGET_EPIC)"; fi
    # Implementers start YUKTA_WARM_DELAY seconds apart (20): the first one writes the cache entry for the prompt prefix
    # (system prompt and tools), the next ones find it. ponytail: a delay, not a wait for the first response.
    delay="${YUKTA_WARM_DELAY:-20}"
    if ((delay > 0)); then
      exec 8>"$repo/.yukta-dev/implement.lock"
      flock 8
      last="$(cat "$repo/.yukta-dev/implement.last" 2>/dev/null || echo 0)"
      wait=$((last + delay - $(date +%s)))
      ((wait > 0)) && sleep "$wait"
      date +%s >"$repo/.yukta-dev/implement.last"
      exec 8>&-
    fi
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
    git_locked git push -q -u origin "issue/$n" || exit 1
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
  epic-list)
    login="$("$gh" api user -q .login)"
    subs="$("$gh" api "repos/$gh_repo/issues/$YUKTA_EPIC/sub_issues" --paginate | jq -s 'add // []')" || nope "epic #$YUKTA_EPIC not found"
    ready="$("$gh" project item-list 5 --owner infenia --limit 700 --format json -q '[.items[] | select(.status == "Ready") | .content.number]')"
    list="$(jq -c --arg l "$login" --argjson ready "$ready" --arg root "$(dirname "$repo")" '
      [.[] | select(.state == "open" and (.assignees | any(.login == $l)) and (.number as $n | $ready | index($n) != null))
       | {issue: (.number | tostring), worktree: ($root + "/yukta-issue-" + (.number | tostring))}]' <<<"$subs")"
    # a session id for each issue's implementer, so its fixers can resume it
    out="[]"
    while IFS= read -r item; do
      out="$(jq -c --argjson i "$item" --arg s "$(cat /proc/sys/kernel/random/uuid)" '. + [$i + {implSession: $s}]' <<<"$out")"
    done < <(jq -c '.[]' <<<"$list")
    open="$(jq -r '[.[] | select(.state == "open")] | length' <<<"$subs")"
    # only JSON on stdout: the node's stderr is merged into it
    jq -n -c --argjson issues "$out" --arg note "epic #$YUKTA_EPIC: $(jq length <<<"$out") of $open open sub-issues are assigned to $login and Ready" \
      '{issues: $issues, note: $note}'
    ;;
  epic-pause)
    count="${YUKTA_BAD_COUNT:-0}"
    if ((count < 2)); then echo "epic-escalation: $count issue run(s) ended ESCALATE, not enough to pause"; exit 0; fi
    echo "warning: epic-escalation: $count issue runs ended ESCALATE; pausing the epic and the runs below it" \
      "(yk resume ${YUKTA_EXECUTION_ID:0:8} --tree to go on, yk stop ${YUKTA_EXECUTION_ID:0:8} --tree to give up)"
    echo "count=$count" >>"$state/epic-pause.log"
    # the server's own port and key directory, so yk reaches the server this node runs on
    [[ -z "${SERVER_PORT:-}" ]] || export YUKTA_DEV_PORT="$SERVER_PORT"
    "$here/../yk" pause "${YUKTA_EXECUTION_ID:?}" --tree || echo "could not pause the epic" >&2
    ;;
  *) echo "unknown action '$action'" >&2; exit 2 ;;
esac
