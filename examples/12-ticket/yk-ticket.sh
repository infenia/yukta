# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# yk ticket: the commands of the ticket pipeline (Epic #1872). Sourced by 11-dogfood/yk, which has the helpers it uses (ensure_server, find_cli,
# watch_owned, start_notifier, cmd_run_control, cmd_tree); examples/12-ticket/yk is the same entry for people who stand in this folder.
#
#   yk ticket run <issue> [--detach [--notify]]   run the `ticket` workflow on an issue: a tested change on branch issue/<n> in ../yukta-issue-<n>
#   yk ticket status <issue|execution>            the run's tree (which node runs or failed) and its last events
#   yk ticket resume|stop <issue|execution>       continue a paused run / end it (a drain, like yk stop --tree)
# Approvals use `yk approve|reject <execution>` as the other gates do. The run dir is .yukta-dev/ticket/<run_id>/ (see README.md).

# render_ticket_session OUT: the `dogfood` session plus the workflows `ticket` and `step`, __TICKET__ replaced by the folder of ticketkit
# (the main checkout the dev server runs when it already has this folder, else this checkout)
render_ticket_session() {
  local t="$DEV_HOME/main/examples/12-ticket"
  [[ -f "$t/session.json" ]] || t="$here/../12-ticket"
  t="$(cd "$t" && pwd)"
  render_session "$1.base"
  jq --arg t "$t" -s '.[0] * {workflows: (.[0].workflows + (.[1].workflows | walk(if type == "string" then split("__TICKET__") | join($t) else . end)))}' "$1.base" "$t/session.json" >"$1"
  rm -f "$1.base"
}

# ticket_last ISSUE: "<run_id> <execution>" of the latest run of an issue
ticket_last() { cat "$STATE_DIR/ticket-last-$1" 2>/dev/null || die "no ticket run of issue $1 started from this checkout"; }

ticket_run() {
  local mode=watch notify=0 issue="" root run_id exec_id cli tmp wt
  while (($#)); do
    case "$1" in
      --detach) mode=detach; shift ;;
      --notify) notify=1; shift ;;
      -*) die "unknown option '$1' (yk ticket run <issue> [--detach [--notify]])" ;;
      *) [[ -z "$issue" ]] || die "one issue at a time"; issue="$1"; shift ;;
    esac
  done
  [[ "$issue" =~ ^[0-9]+$ ]] || die "usage: yk ticket run <issue number> [--detach [--notify]]"
  ((notify == 0)) || [[ "$mode" == detach ]] || die "--notify needs --detach: a run you wait for reports to you anyway"
  need jq; need python3
  root="$(git rev-parse --show-toplevel)"
  run_id="$issue-$(date -u +%Y%m%dT%H%M%SZ)"
  wt="$(cd "$root" && cd "$(dirname "$(jq -r '.git.worktree' "$here/../12-ticket/project.json" | sed "s/{n}/$issue/")")" && pwd)/yukta-issue-$issue"
  ensure_server
  cli="$(find_cli)"
  mkdir -p "$STATE_DIR"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  render_ticket_session "$tmp/session.json"
  "$cli" session apply "$tmp/session.json" >"$tmp/apply.out" 2>&1 || { cat "$tmp/apply.out" >&2; die "session apply failed"; }
  exec_id="$("$cli" -o json workflow start dogfood ticket --payload "$(jq -nc --arg p "$root" --arg i "$issue" --arg r "$run_id" --arg s "$STATE_DIR" \
      --arg w "$wt" --arg bin "${YUKTA_CLAUDE_BIN:--}" --arg gs "${TICKET_GATE_STEP:--}" --arg gf "${TICKET_GATE_FINAL:--}" --arg hc "${TICKET_HEAL_CMD:--}" --arg bc "${TICKET_BASELINE_CMD:--}" --arg ev "${YUKTA_TICKET_EVENTS:-$DEV_HOME/ticket-events.jsonl}" '
      {project: $p, issue: $i, runId: $r, stateDir: $s, run: "\($s)/ticket/\($r)", worktree: $w, claudeBin: $bin, gateStep: $gs, gateFinal: $gf, healCmd: $hc, baselineCmd: $bc, events: $ev}')" \
    | jq -r '.data.executionId // .executionId')"
  [[ -n "$exec_id" && "$exec_id" != null ]] || die "could not start the ticket workflow"
  echo "$run_id $exec_id" >"$STATE_DIR/ticket-last-$issue"
  echo "STARTED execution=$exec_id session=dogfood run=$STATE_DIR/ticket/$run_id worktree=$wt"
  ((notify == 0)) || YUKTA_PROJECT="$root" YUKTA_SCOPE="ticket:$issue" start_notifier dogfood "$exec_id"
  [[ "$mode" == detach ]] && return 0
  YUKTA_PROJECT="$root" YUKTA_SCOPE="ticket:$issue" watch_owned dogfood "$exec_id"
}

cmd_ticket() {
  local sub="${1:-}" target exec_id run_id
  (($#)) && shift
  case "$sub" in
    run) ticket_run "$@" ;;
    status | resume | stop)
      target="${1:?usage: yk ticket $sub <issue|execution>}"
      if [[ "$target" =~ ^[0-9]{1,8}$ ]]; then read -r run_id exec_id < <(ticket_last "$target"); else exec_id="$target" run_id=""; fi
      case "$sub" in
        status)
          cmd_tree "$exec_id"
          [[ -z "$run_id" ]] || tail -n 5 "$STATE_DIR/ticket/$run_id/events.jsonl" 2>/dev/null | jq -c '{role, model, step_id, attempt, cost_usd, cache_read_tokens}' || true ;;
        *) cmd_run_control "$sub" "$exec_id" --tree ;;
      esac ;;
    *) die "usage: yk ticket run <issue> [--detach [--notify]] | status|resume|stop <issue|execution>" ;;
  esac
}
