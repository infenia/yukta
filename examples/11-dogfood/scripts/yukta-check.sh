#!/usr/bin/env bash
# Run the staged `gradle check` workflow on a Yukta server and report the result.
# A thin wrapper around the yukta CLI.
#
# The staged gradle-check is supervisor-driven: each stage is its own workflow with explicit exits (see
# stage_workflows in _lib.sh); --no-heal, gradle args and WORKFLOW use the plain single workflow.
#
# Usage: [WORKFLOW=quick-check] yukta-check.sh [--detach|--watch] [--no-heal] [--fixer fresh|fork|auto]
#                                              [-p project-path] [-m module] [gradle args...]
#   (no args)         staged gradle-check, whole project
#   -m <module>       staged gradle-check scoped to one module (gradle path, e.g. core or
#                     plugins:processors:decision)
#   <gradle args>     run exactly these args as one stage, e.g. :core:test --tests X, or build
#   -p project-path   checkout to check (default: current git toplevel)
#   --detach          start the run, print "STARTED execution=<id> session=<id>" and exit; follow it
#                     with yukta-watch.sh (the form for Claude Code under the Monitor tool). The staged
#                     check runs in the background and prints "STARTED session=<id> log=<file>"; follow
#                     it with yukta-watch.sh <session> <file>
#   --watch           follow the run, errors and warnings only (yukta-watch.sh --quiet), instead of the
#                     full log; the cheap way to follow a run (the staged check prints STAGE lines)
#   --no-heal         the plain fail-fast chain: no self-healing (the staged gradle-check heals by default)
#   --fixer MODE      who attempts the Claude fixes of a failed stage: fresh (a new headless claude),
#                     fork (a fork of this Claude Code session, $CLAUDE_CODE_SESSION_ID) or auto (the
#                     default: fresh first, fork for the second attempt; fresh when there is no session)
#   WORKFLOW          override the workflow name (e.g. quick-check: formatting + Go vet)
#
# Server: $YUKTA_SERVER_URL, else .yukta-dev/current.env written by yukta-worktree.sh.
# Exit code: 0 only when the execution ends COMPLETED/SUCCESS. The last line is machine readable:
#   RESULT status=<STATUS> failed_node=<node|-> execution=<id> session=<id>
set -euo pipefail
here="$(dirname "${BASH_SOURCE[0]}")"
source "$here/_lib.sh"

need jq
project_arg="" module="" mode=logs heal=1 fixer=auto
while (($#)); do
  case "$1" in
    -p) project_arg="${2:?-p needs a path}"; shift 2 ;;
    -m) module="${2:?-m needs a module}"; shift 2 ;;
    --detach) mode=detach; shift ;;
    --watch) mode=watch; shift ;;
    --no-heal) heal=0; shift ;;
    --fixer) fixer="${2:?--fixer needs fresh, fork or auto}"; shift 2 ;;
    --) shift; break ;;
    *) break ;;
  esac
done
[[ "$fixer" =~ ^(fresh|fork|auto)$ ]] || die "invalid --fixer '$fixer' (fresh, fork or auto)"
[[ -z "$module" || "$module" =~ ^[A-Za-z0-9][A-Za-z0-9:_-]*$ ]] || die "invalid module '$module'"
[[ -z "$module" || $# -eq 0 ]] || die "use either -m <module> or gradle args, not both"
project="$(cd "${project_arg:-$(git rev-parse --show-toplevel)}" && pwd)"

load_server
cli="$(find_cli)"

branch="$(git -C "$project" rev-parse --abbrev-ref HEAD | tr -c 'A-Za-z0-9\n' '-')"
# The path hash keeps checkouts apart on a shared server (feature/a vs feature-a, two clones).
session="check-$branch-$(printf '%s' "$project" | sha1sum | cut -c1-8)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Point the template at this checkout and give the session its own id.
workflow="${WORKFLOW:-gradle-check}"
scope="${module:-all}"
if (($#)); then
  workflow=gradle-run
  scope="args:$(printf '%.60s' "$*")"
fi
# Self-healing applies to the staged gradle-check only (see stage_workflows in _lib.sh).
heal_dir="$STATE_DIR/heal/$session"
healsh="$REPO_ROOT/examples/11-dogfood/scripts/yukta-heal.sh"
rm -rf "$heal_dir"
fork_session="${CLAUDE_CODE_SESSION_ID:-}"
m1=fresh m2=fork
[[ "$fixer" == fresh || -z "$fork_session" ]] && m2=fresh
[[ "$fixer" == fork && -n "$fork_session" ]] && m1=fork
{
  render_session "$REPO_ROOT/examples/11-dogfood/session.json" "$project" \
    | jq --arg s "$session" '.sessionId=$s | .name=$s' \
    | if [[ -n "$module" ]]; then scope_to_module "$module"; else cat; fi \
    | if (($#)); then splice_args "$@"; else cat; fi \
    | if ((heal)) && [[ "$workflow" == gradle-check ]]; then
      stage_workflows "$(cat "$REPO_ROOT/examples/11-dogfood/heal.json")" "$module" "$project" \
        "$healsh" "$heal_dir"
    else cat; fi
} >"$tmp/session.json"

echo ">> server $YUKTA_SERVER_URL, session $session, project $project" >&2
"$cli" session apply "$tmp/session.json" >"$tmp/apply.out" 2>&1 \
  || { cat "$tmp/apply.out" >&2; die "session apply failed"; }
# a CLI built before 'session apply' existed prints the session help and exits 0
! grep -q "Available Commands" "$tmp/apply.out" \
  || die "stale CLI (session apply printed help); rebuild it: ./gradlew :cli:goBuild"
if ((heal)) && [[ "$workflow" == gradle-check ]]; then
  # Staged, supervisor-driven check: one small workflow per stage (see stage_workflows). The server's
  # rule file (examples/11-dogfood/rules.json) restarts a stage whose repair worked; this loop moves
  # from stage to stage and calls the Claude fixer on an escalation.
  export cli project module healsh heal_dir fork=$fork_session m1 m2
  case "$mode" in
    detach)
      mkdir -p "$STATE_DIR/check"
      log="$STATE_DIR/check/$session.log"
      cp "$tmp/session.json" "$STATE_DIR/check/$session.json"
      ( run_stages "$session" "$STATE_DIR/check/$session.json" >"$log" 2>&1 ) </dev/null &
      echo "STARTED session=$session log=$log"
      exit 0
      ;;
    *) run_stages "$session" "$tmp/session.json"; exit $? ;;
  esac
fi
exec_id="$("$cli" -o json workflow start "$session" "$workflow" | jq -r '.data.executionId // .executionId')"
[[ -n "$exec_id" && "$exec_id" != null ]] || die "could not start workflow"

case "$mode" in
  detach)
    echo "STARTED execution=$exec_id session=$session"
    exit 0
    ;;
  watch)
    echo "STARTED execution=$exec_id session=$session"
    export YUKTA_SCOPE="$scope"
    exec "$here/yukta-watch.sh" "$session" "$exec_id" --quiet
    ;;
esac

echo ">> execution $exec_id" >&2
# The log stream can 404 for a moment right after start; retry briefly.
for _ in 1 2 3 4 5; do
  "$cli" logs "$session" "$exec_id" && break
  sleep 2
done

record_durations "$session" "$exec_id" "$scope"
print_result "$session" "$exec_id"
