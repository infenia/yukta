# Moved out of yk during the rebuild: yk run issue|epic (needs _lib.issue.sh, yukta-issue.sh, nav.sh)
# ---- layered workflows: an issue from claim to draft PR ----

# yk run issue N [--detach] [--no-pause before-implement,before-pr]
# yk run epic N  [--detach] [--no-pause before-implement,before-pr,between-issues]
# issue: implements one assigned, Ready issue in its own worktree (../yukta-issue-N): a headless Claude does the
# work (after a code-navigation index of the worktree), the self-healing check runs on the worktree
# (a triage picks the model and effort of fresh fixer sessions), and a draft PR opens once you approve. epic: the same for every open, assigned, Ready sub-issue of epic N, two at a time.
# The checkpoints wait for you (yk signals / yk approve|reject <e> <signal>): before-implement (edit
# .yukta-dev/issue-N/brief.md first, then approve), before-pr, and for an epic between-issues (each issue
# starts when you approve it). Budgets: YUKTA_BUDGET_ISSUE and YUKTA_BUDGET_EPIC (USD) stop the spending.
cmd_run() {
  die "yk run is hidden during the rebuild: the issue and epic workflows called the old generated check and come back after heal"
  local what="${1:-}"
  [[ "$what" == issue || "$what" == epic ]] || die "usage: yk run issue|epic <number> [--detach] [--no-pause <checkpoints>]"
  shift
  run_layered "$what" "$@"
}

# run_layered KIND N [--detach] [--no-pause a,b]
run_layered() {
  local kind="$1" n="${2:?usage: yk run $1 <number> [--detach] [--no-pause <checkpoints>]}" mode=watch nopause="" impl=1 pr=1 between=1 p
  shift 2
  [[ "$n" =~ ^[0-9]+$ ]] || die "the $kind is a number, not '$n'"
  while (($#)); do
    case "$1" in
      --detach) mode=detach; shift ;;
      --no-pause) nopause="${2:?--no-pause needs checkpoints}"; shift 2 ;;
      *) die "unknown option '$1'" ;;
    esac
  done
  for p in ${nopause//,/ }; do
    case "$p" in
      before-implement) impl=0 ;;
      before-pr) pr=0 ;;
      between-issues) [[ "$kind" == epic ]] || die "between-issues is a checkpoint of an epic"; between=0 ;;
      *) die "unknown checkpoint '$p' (before-implement, before-pr$([[ "$kind" == epic ]] && echo ', between-issues'))" ;;
    esac
  done
  need jq
  ensure_server
  # tmp is global: the EXIT trap runs after this function's locals are gone
  local cli workflow payload exec_id
  cli="$(find_cli)"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  mkdir -p "$STATE_DIR"
  if [[ "$kind" == issue ]]; then
    workflow="run-issue$(issue_variant "$impl" "$pr")"  # a nav step indexes the worktree before implement
    payload="$(jq -nc --arg i "$n" --arg r "$REPO_ROOT" --arg w "$(dirname "$REPO_ROOT")/yukta-issue-$n" '{issue: $i, repo: $r, worktree: $w}')"
    rm -rf "$(dirname "$REPO_ROOT")/yukta-issue-$n/.yukta-dev/heal"
  else
    workflow="run-epic$(epic_variant "$impl" "$pr" "$between")"
    payload="$(jq -nc --arg e "$n" --arg r "$REPO_ROOT" '{epic: $e, repo: $r}')"
  fi
  dev_session "$here/scripts/yukta-heal.sh" "$here/scripts/yukta-issue.sh" >"$tmp/session.json"
  echo ">> server $YUKTA_SERVER_URL, session yukta-dev, $kind #$n" >&2
  "$cli" session apply "$tmp/session.json" >"$tmp/apply.out" 2>&1 || { cat "$tmp/apply.out" >&2; die "session apply failed"; }
  exec_id="$("$cli" -o json workflow start yukta-dev "$workflow" --payload "$payload" | jq -r '.data.executionId // .executionId')"
  [[ -n "$exec_id" && "$exec_id" != null ]] || die "could not start $workflow"
  echo "STARTED execution=$exec_id session=yukta-dev"
  # an issue run heals in its worktree: say where, for the RESULT of this run (a later yk watch or yk result too)
  if [[ "$kind" == issue ]]; then
    mkdir -p "$DEV_HOME/heal-records"
    echo "$(dirname "$REPO_ROOT")/yukta-issue-$n/.yukta-dev/heal/check" >"$DEV_HOME/heal-records/$exec_id"
  fi
  ((impl)) && echo "checkpoint before-implement: edit $REPO_ROOT/.yukta-dev/issue-<n>/brief.md if you want, then: yk approve ${exec_id:0:8} before-implement" >&2
  ((pr)) && echo "checkpoint before-pr: yk approve ${exec_id:0:8} before-pr (or yk reject ...)" >&2
  [[ "$kind" == epic ]] && ((between)) && echo "checkpoint between-issues: yk approve ${exec_id:0:8} between-issues, once for each issue" >&2
  [[ "$mode" == detach ]] && return 0
  YUKTA_SCOPE="$kind-$n" cmd_watch yukta-dev "$exec_id"
}

