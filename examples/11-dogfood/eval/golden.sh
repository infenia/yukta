#!/usr/bin/env bash
# The golden set: replay a few frozen issues in every method, repeated, to compare methods on the same tasks and to check a loop change.
#
#   golden.sh status                 the set, the loop commit of this checkout, the replays so far and the budget
#   golden.sh validate [issue...]    each grader must fail on the frozen base and pass with its reference fix (eval.sh validate)
#   golden.sh run [--trials 3] [--methods A,C] [--issues 1207,...] [--dry-run] [--force]
#   golden.sh report [--loop SHA] [--against SHA] [--min-issues 5]
#
# A replay runs, for the loop commit of THIS checkout (the dogfood scripts under test), every issue of the set in every method, K trials each, on
# isolated clones of the issue's frozen base commit (eval.sh, nothing touches GitHub), serially. The order is seeded and shuffled per issue and
# trial, so no method always goes first. A replay is meant once per major loop change: a loop commit that already has a complete replay is refused
# unless --force (a new replay of it, more trials), and an uncommitted checkout is refused, because a dirty tree is not a version. A replay that
# stopped (budget, interruption) is resumed by the next `run`: runs already recorded are skipped.
# Budget: YUKTA_GOLDEN_BUDGET_TOTAL (30 USD, separate from the loop's $60), EVAL_BUDGET (5 USD) per session; no run starts at the limit.
# Where: $YUKTA_GOLDEN_DIR (default <checkout>/.yukta-dev/golden): ledger.jsonl and runs/<loop>/<replay>/<issue>/runs/<method><trial>.
# A run counts as a success when its code is correct (the hidden test and the suite pass) and, for C, the workflow ended SUCCESS.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$here" rev-parse --show-toplevel)"
GOLDEN_DIR="${YUKTA_GOLDEN_DIR:-${YUKTA_DEV_STATE_DIR:-$repo_root/.yukta-dev}/golden}"
LEDGER="$GOLDEN_DIR/ledger.jsonl"
SET="${YUKTA_GOLDEN_SET:-$here/golden/set.json}"
BUDGET="${YUKTA_GOLDEN_BUDGET_TOTAL:-30}"
die() { echo "golden: $*" >&2; exit 1; }
command -v jq >/dev/null || die "jq is required"

ledger_all() { [[ -s "$LEDGER" ]] && cat "$LEDGER" || true; }
ledger_append() { mkdir -p "$GOLDEN_DIR"; (flock 9; printf '%s\n' "$1" >>"$LEDGER") 9>"$GOLDEN_DIR/ledger.lock"; }
loop_sha() { # the loop commit of this checkout, -dirty with uncommitted changes in the dogfood directory (YUKTA_GOLDEN_SHA: tests)
  [[ -z "${YUKTA_GOLDEN_SHA:-}" ]] || { echo "$YUKTA_GOLDEN_SHA"; return; }
  local sha
  sha="$(git -C "$here" rev-parse --short=9 HEAD 2>/dev/null || echo unknown)"
  [[ -z "$(git -C "$here" status --porcelain -- "$here/.." 2>/dev/null)" ]] || sha+="-dirty"
  echo "$sha"
}
spent() { ledger_all | jq -s '[.[] | select(.type == "run") | .cost_usd // 0] | add // 0'; }
manifest_issues() { jq -r '.issues[] | "\(.issue) \(.base)"' "$SET"; }

cmd_status() {
  local sha
  sha="$(loop_sha)"
  echo "Golden set ($SET):"
  while read -r i base; do
    printf '  #%s base %s grader %s\n' "$i" "${base:0:9}" "$([[ -f "$here/issues/$i/grade.env" ]] && echo present || echo MISSING)"
  done < <(manifest_issues)
  echo "Loop commit of this checkout: $sha"
  echo "Replays:"
  ledger_all | jq -rs '[.[] | select(.type == "replay")] | map(.id) | .[]' | while read -r id; do
    ledger_all | jq -rs --arg id "$id" '
      (map(select(.type == "replay" and .id == $id)) | first) as $r
      | (map(select(.type == "run" and .replay == $id))) as $runs
      | (map(select(.type == "replay-end" and .id == $id)) | last | .state // "running") as $st
      | "  \($id): \($runs | length) of \($r.planned) runs, $\((($runs | map(.cost_usd // 0) | add // 0) * 100 | round) / 100), \($st)"'
  done
  ledger_all | jq -s -e --arg sha "$sha" 'any(.[]; .type == "replay-end" and .state == "complete" and .loop_sha == $sha)' >/dev/null 2>&1 \
    && echo "This loop commit has a complete replay." \
    || echo "Due: this loop commit ($sha) has no complete replay (golden.sh run)."
  echo "Budget: \$$(spent) of \$$BUDGET"
}

cmd_validate() {
  local only=("$@") i base rc=0
  while read -r i base; do
    [[ ${#only[@]} -eq 0 || " ${only[*]} " == *" $i "* ]] || continue
    echo "== validating the grader of #$i on ${base:0:9}"
    EVAL_ISSUE="$i" EVAL_BASE="$base" EVAL_DIR="$GOLDEN_DIR/validate/$i" "$here/eval.sh" validate || rc=1
  done < <(manifest_issues)
  return $rc
}

cmd_run() {
  local trials=3 methods="A,C" issues="" dry=0 force=0 sha plan=() n id trial i base m rid state
  while (($#)); do
    case "$1" in
      --trials) trials="${2:?--trials needs a number}"; shift 2 ;;
      --methods) methods="${2:?--methods needs a list}"; shift 2 ;;
      --issues) issues="${2:?--issues needs a list}"; shift 2 ;;
      --dry-run) dry=1; shift ;;
      --force) force=1; shift ;;
      *) die "unknown option '$1'" ;;
    esac
  done
  [[ "$trials" =~ ^[0-9]+$ ]] && ((trials >= 1)) || die "--trials is a positive number"
  [[ ",$methods," != *,B,* ]] || die "--methods: method B is retired, it is part of C now"
  [[ "$methods" =~ ^(A|C)(,(A|C))*$ ]] || die "--methods is a list of A and C"
  sha="$(loop_sha)"
  [[ "$sha" != *-dirty && "$sha" != unknown ]] || ((force)) || die "this checkout has uncommitted changes in examples/11-dogfood: a dirty tree is not a loop version. Commit first, or --force."
  # which replay: resume the latest one of this loop commit that is not complete (stopped or interrupted), else a new one (refused after a complete one without --force)
  rid="$(ledger_all | jq -rs --arg sha "$sha" '
    (map(select(.type == "replay-end" and .state == "complete") | .id)) as $done
    | (map(select(.type == "replay" and .loop_sha == $sha and (.id | IN($done[]) | not))) | last | .id) // empty')"
  if [[ -z "$rid" ]]; then
    if ledger_all | jq -s -e --arg sha "$sha" 'any(.[]; .type == "replay-end" and .state == "complete" and .loop_sha == $sha)' >/dev/null 2>&1 && ((force == 0)); then
      die "loop commit $sha already has a complete replay; a replay is for a loop change (use --force for another one)"
    fi
    n=$(($(ledger_all | jq -s --arg sha "$sha" '[.[] | select(.type == "replay" and .loop_sha == $sha)] | length') + 1))
    rid="$sha-$n"
  fi
  # the plan: trial by trial, issue by issue, the methods in a seeded shuffled order
  for ((trial = 1; trial <= trials; trial++)); do
    while read -r i base; do
      [[ -z "$issues" || ",$issues," == *",$i,"* ]] || continue
      for m in $(for x in ${methods//,/ }; do printf '%s %s\n' "$(printf '%s|%s|%s|%s' "$sha" "$i" "$trial" "$x" | sha256sum | cut -c1-16)" "$x"; done | sort | awk '{print $2}'); do
        plan+=("$i:$m:$trial:$base")
      done
    done < <(manifest_issues)
  done
  ((${#plan[@]})) || die "nothing to replay: no issue of the set matches --issues $issues"
  echo "Replay $rid: ${#plan[@]} run(s), $trials trial(s), methods $methods, spent \$$(spent) of \$$BUDGET"
  printf '  %s\n' "${plan[@]%:*}"
  ((dry)) && return 0
  ledger_all | jq -s -e --arg id "$rid" 'any(.[]; .type == "replay" and .id == $id)' >/dev/null 2>&1 \
    || ledger_append "$(jq -nc --arg id "$rid" --arg sha "$sha" --argjson planned "${#plan[@]}" --argjson trials "$trials" --arg methods "$methods" --arg t "$(date -u +%FT%TZ)" \
      '{type: "replay", id: $id, loop_sha: $sha, planned: $planned, trials: $trials, methods: $methods, time: $t}')"
  state=complete
  for item in "${plan[@]}"; do
    IFS=: read -r i m trial base <<<"$item"
    if ledger_all | jq -s -e --arg id "$rid" --argjson i "$i" --arg m "$m" --argjson t "$trial" 'any(.[]; .type == "run" and .replay == $id and .issue == $i and .method == $m and .trial == $t)' >/dev/null 2>&1; then
      echo "skip #$i $m$trial (already run)"; continue
    fi
    if awk -v s="$(spent)" -v b="$BUDGET" 'BEGIN { exit !(s + 0 >= b + 0) }'; then
      state=stopped; echo "STOP: the replay has spent \$$(spent) of its \$$BUDGET budget"; break
    fi
    echo ">> #$i $m$trial"
    res="$(EVAL_ISSUE="$i" EVAL_BASE="$base" EVAL_DIR="$GOLDEN_DIR/runs/$sha/$rid/$i" "$here/eval.sh" run "$m" "$trial" 2>"$GOLDEN_DIR/last-run.err" || true)"
    ledger_append "$(jq -c --arg rid "$rid" --arg sha "$sha" --argjson i "$i" --arg m "$m" --argjson t "$trial" --arg tm "$(date -u +%FT%TZ)" '
      {type: "run", replay: $rid, loop_sha: $sha, issue: $i, method: $m, trial: $t, time: $tm, cost_usd: (.total.cost // 0),
       tokens: ((.total.input // 0) + (.total.cache_creation // 0) + (.total.cache_read // 0) + (.total.output // 0)), wall_s: (.wall_s // 0),
       correct: ((.grade.hidden_pass // false) and (.grade.suite_pass // false)),
       delivered: (if $m == "A" then null else ((.answer // "") | test("status=SUCCESS")) end),
       success: (((.grade.hidden_pass // false) and (.grade.suite_pass // false)) and ($m == "A" or ((.answer // "") | test("status=SUCCESS")))),
       backend: (.nav.backend // null)}' <<<"${res:-null}" 2>/dev/null || jq -nc --arg rid "$rid" --argjson i "$i" --arg m "$m" --argjson t "$trial" '{type: "run", replay: $rid, issue: $i, method: $m, trial: $t, cost_usd: 0, success: false, failed: true}')"
    echo "   $(ledger_all | jq -rs 'map(select(.type == "run")) | last | "cost $\(.cost_usd) success=\(.success)"')"
  done
  ledger_append "$(jq -nc --arg id "$rid" --arg sha "$sha" --arg st "$state" --arg t "$(date -u +%FT%TZ)" '{type: "replay-end", id: $id, loop_sha: $sha, state: $st, time: $t}')"
  echo "Replay $rid $state. golden.sh report"
}

case "${1:-}" in
  status) cmd_status ;;
  validate) shift; cmd_validate "$@" ;;
  run) shift; cmd_run "$@" ;;
  report) shift; python3 -I "$here/golden-report.py" --ledger "$LEDGER" "$@" ;;
  *) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; exit 2 ;;
esac
