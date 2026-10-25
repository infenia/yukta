#!/usr/bin/env bash
# The improvement loop's launcher: draw a method for an issue at random (balanced by size), run it, record it.
#
#   loop.sh plan <n> [--method M] [--dry-run]   size, loop-changing flag and the drawn method; the draw is written
#                                               to the ledger unless --dry-run. --method forces one (recorded, and
#                                               left out of the method comparison)
#   loop.sh pick <n> [--method M] [--dry-run]   budget gate, eligibility, plan, run the method, record the outcome
#   loop.sh next                                your assigned, Ready issues, by priority, with their size
#   loop.sh total                               dollars spent so far and the limit
#   loop.sh ledger                              one line per draw and outcome
#
# Every draw is appended to the ledger BEFORE the run starts, so the assignment cannot depend on how the run
# goes. An issue that already has a draw keeps its method on a second pick. Draws are block-randomized inside
# each size stratum: a block holds each enabled method once, in a seeded order, so the methods stay balanced
# even with few issues.
#
# Environment: YUKTA_LOOP_METHODS (comma list of A, B, C; default B: A and C are enabled as they land),
#   YUKTA_LOOP_SEED (default: a random seed kept in $LOOP_DIR/seed), YUKTA_LOOP_BUDGET_TOTAL (60 USD),
#   YUKTA_LOOP_BUDGET_SESSION (5 USD per Claude session), YUKTA_LOOP_DIR (default $DEV_HOME/loop),
#   YUKTA_LOOP_RUNNER (tests: a command that stands in for the method run), YUKTA_GH, YUKTA_ISSUES_REPO.
# Methods: A  one Claude Code session fixes the issue itself, Yukta only checks.
#          B  a Claude Code session orchestrates `yk run issue`; slim sub-flow sessions do the work.
#          C  B with a code-navigation backend for the sub-flows.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/_lib.sh"
source "$here/_usage.sh"
LOOP_DIR="${YUKTA_LOOP_DIR:-$DEV_HOME/loop}"
LEDGER="$LOOP_DIR/ledger.jsonl"
gh="${YUKTA_GH:-gh}"
gh_repo="${YUKTA_ISSUES_REPO:-infenia/yukta}"
BUDGET_TOTAL="${YUKTA_LOOP_BUDGET_TOTAL:-60}"
BUDGET_SESSION="${YUKTA_LOOP_BUDGET_SESSION:-5}"
need jq

ledger_all() { [[ -s "$LEDGER" ]] && cat "$LEDGER" || true; }
ledger_append() { # JSON
  mkdir -p "$LOOP_DIR"
  (flock 9; printf '%s\n' "$1" >>"$LEDGER") 9>"$LOOP_DIR/ledger.lock"
}

loop_seed() {
  if [[ -n "${YUKTA_LOOP_SEED:-}" ]]; then echo "$YUKTA_LOOP_SEED"; return; fi
  mkdir -p "$LOOP_DIR"
  [[ -s "$LOOP_DIR/seed" ]] || od -An -N8 -tx1 /dev/urandom | tr -d ' \n' >"$LOOP_DIR/seed"
  cat "$LOOP_DIR/seed"
}

# the loop's own version: the commit of the checkout this script runs from, "-dirty" with uncommitted changes in examples/11-dogfood
loop_sha() {
  local sha
  sha="$(git -C "$here" rev-parse --short=9 HEAD 2>/dev/null || echo unknown)"
  [[ -z "$(git -C "$here" status --porcelain -- "$here/.." 2>/dev/null)" ]] || sha+="-dirty"
  echo "$sha"
}

enabled_methods() {
  local m out=()
  IFS=, read -ra out <<<"${YUKTA_LOOP_METHODS:-B}"
  for m in "${out[@]}"; do [[ "$m" =~ ^[ABC]$ ]] || die "YUKTA_LOOP_METHODS holds A, B or C, not '$m'"; done
  printf '%s\n' "${out[@]}"
}

issue_json() { "$gh" issue view "$1" -R "$gh_repo" --json number,title,body,labels,state,assignees; }

spent_total() {
  ledger_all | jq -s '[.[] | select(.type == "outcome") | (.cost_usd // 0) + (.analyzer_cost_usd // 0)] | add // 0'
}

gate() {
  local spent
  spent="$(spent_total)"
  if awk -v s="$spent" -v b="$BUDGET_TOTAL" 'BEGIN { exit !(s + 0 >= b + 0) }'; then
    die "the loop has spent \$$spent of its \$$BUDGET_TOTAL budget: review it (yk loop report), then raise YUKTA_LOOP_BUDGET_TOTAL"
  fi
}

# eligible N JSON: open, assigned to you, Ready on the project (the same checks as the claim step of yukta-issue.sh)
eligible() {
  local n="$1" json="$2" login status
  [[ "$(jq -r .state <<<"$json")" == OPEN ]] || die "issue #$n is not open"
  login="$("$gh" api user -q .login)"
  jq -e --arg l "$login" '.assignees | any(.login == $l)' <<<"$json" >/dev/null || die "issue #$n is not assigned to $login"
  status="$("$gh" project item-list 5 --owner infenia --limit 700 --format json -q ".items[] | select(.content.number == $n) | .status")"
  [[ "$status" == Ready ]] || die "issue #$n is ${status:-not on the project}, not Ready"
}

# loop_changing JSON: does the issue change the dogfood loop itself? labels loop-changing / loop-neutral override
loop_changing() {
  jq -r '(.labels // [] | map(.name)) as $l
    | if ($l | index("loop-changing")) then "true" elif ($l | index("loop-neutral")) then "false"
      elif ((.body // "") | test("examples/11-dogfood|build-logic|\\byk\\b|yukta-(heal|issue)|_claude\\.sh|controlbus")) then "true"
      else "false" end' <<<"$1"
}

# draw SIZE REGIME METHODS...: prints "<method> <block> <position>", the next slot of the stratum's seeded blocks
draw() {
  local size="$1" regime="$2" seed d k b p m
  shift 2
  seed="$(loop_seed)"
  d="$(ledger_all | jq -s --arg s "$size" --arg r "$regime" '[.[] | select(.type == "draw" and .size == $s and .regime == $r and (.forced | not))] | length')"
  k=$#
  b=$((d / k))
  p=$((d % k))
  for m in "$@"; do
    printf '%s %s\n' "$(printf '%s|%s|%s|%s' "$seed" "$size" "$b" "$m" | sha256sum | cut -c1-16)" "$m"
  done | sort | awk -v b="$b" -v p="$p" 'NR == p + 1 { print $2, b, p }'
}

cmd_plan() {
  local n="${1:?usage: loop.sh plan <n> [--method M] [--dry-run]}" forced="" dry=0 json size_json size score ssrc changing existing method regime id bp methods
  shift
  while (($#)); do
    case "$1" in
      --method) forced="${2:?--method needs A, B or C}"; shift 2 ;;
      --dry-run) dry=1; shift ;;
      *) die "unknown option '$1'" ;;
    esac
  done
  [[ -z "$forced" || "$forced" =~ ^[ABC]$ ]] || die "--method is A, B or C"
  json="$(issue_json "$n")"
  size_json="$("$here/issue-size.sh" - <<<"$json")"
  size="$(jq -r .size <<<"$size_json")" score="$(jq -r .score <<<"$size_json")" ssrc="$(jq -r .source <<<"$size_json")"
  changing="$(loop_changing "$json")"
  mapfile -t methods < <(enabled_methods)
  regime="$(IFS=,; echo "${methods[*]}")"
  existing="$(ledger_all | jq -s --argjson n "$n" '[.[] | select(.type == "draw" and .issue == $n)] | last // empty')"
  if [[ -n "$existing" && -z "$forced" ]]; then
    jq -c '. + {reused: true}' <<<"$existing"
    return 0
  fi
  if [[ -n "$forced" ]]; then
    method="$forced" bp="0 0"
  else
    read -r method bp < <(draw "$size" "$regime" "${methods[@]}")
  fi
  id="$(date +%s)-$n"
  local rec
  rec="$(jq -nc --arg id "$id" --arg t "$(date -u +%FT%TZ)" --argjson n "$n" --arg size "$size" --argjson score "$score" --arg ssrc "$ssrc" \
    --argjson changing "$changing" --arg method "$method" --argjson forced "$([[ -n "$forced" ]] && echo true || echo false)" \
    --arg regime "$regime" --arg bp "$bp" --arg seed "$(loop_seed)" --arg loop "$(loop_sha)" --arg base "$(git -C "$REPO_ROOT" rev-parse origin/main 2>/dev/null || echo unknown)" \
    '{type: "draw", id: $id, time: $t, issue: $n, size: $size, score: $score, size_source: $ssrc, loop_changing: $changing,
      method: $method, forced: $forced, regime: $regime, block: ($bp | split(" ")[0] | tonumber), position: ($bp | split(" ")[1] | tonumber),
      seed: $seed, loop_sha: $loop, base_sha: $base, nav_backend: null}')"
  ((dry)) || ledger_append "$rec"
  echo "$rec"
}

# run_method N METHOD DIR: runs the method; prints what it prints. DIR gets main.json (the main session's usage)
run_method() {
  local n="$1" method="$2" dir="$3"
  case "$method" in
    B) run_method_b "$n" "$dir" ;;
    *) die "method $method is not available yet (YUKTA_LOOP_METHODS=$YUKTA_LOOP_METHODS)" ;;
  esac
}

# B: a headless Claude Code session (your own setup) orchestrates: it starts yk run issue and reports the RESULT line
run_method_b() {
  local n="$1" dir="$2" yk="$here/../yk"
  (cd "$REPO_ROOT" && env -u CLAUDECODE -u CLAUDE_CODE_SESSION_ID -u CLAUDE_CODE_ENTRYPOINT -u CLAUDE_CODE_SSE_PORT \
    YUKTA_DOGFOOD=off YUKTA_BUDGET_ISSUE="$BUDGET_SESSION" YUKTA_IMPL_BUDGET="$BUDGET_SESSION" \
    "${YUKTA_CLAUDE_BIN:-claude}" -p "Issue #$n must be fixed, and you are the orchestrator, not the implementer: do not edit code yourself. Start Yukta's issue workflow:

  $yk run issue $n --no-pause before-implement,before-pr

Run it in the foreground with the Bash timeout set to 600000 ms. It prints a few lines and ends with a \`RESULT status=...\` line. If the command times out before RESULT, re-attach with \`$yk watch <session> <execution>\` (both are in its STARTED line) the same way. If the run ends ESCALATE, read what it says and stop; do not start it again. Do not commit or push. Finish with the RESULT line and one short sentence." \
    --model "${YUKTA_LOOP_MODEL:-opus}" --no-session-persistence --permission-mode acceptEdits \
    --allowedTools "Read" "Grep" "Glob" "Bash($yk:*)" "Bash(git diff:*)" "Bash(git status:*)" \
    --disallowedTools "Bash(git commit:*)" "Bash(git push:*)" \
    --max-budget-usd "$BUDGET_SESSION" --max-turns 40 --output-format json) >"$dir/main.json" 2>"$dir/main.err" || true
  jq -r '.result // ""' "$dir/main.json" 2>/dev/null || true
}

cmd_pick() {
  local n="${1:?usage: loop.sh pick <n> [--method M] [--dry-run]}" plan_args=() dry=0 json plan id method dir start end out main sub res
  shift
  while (($#)); do
    case "$1" in
      --dry-run) dry=1; plan_args+=("$1"); shift ;;
      --method) plan_args+=("$1" "${2:?--method needs A, B or C}"); shift 2 ;;
      *) die "unknown option '$1'" ;;
    esac
  done
  gate
  json="$(issue_json "$n")"
  eligible "$n" "$json"
  plan="$(cmd_plan "$n" "${plan_args[@]}")"
  echo "$plan"
  ((dry)) && return 0
  id="$(jq -r .id <<<"$plan")" method="$(jq -r .method <<<"$plan")"
  dir="$LOOP_DIR/runs/$id"
  mkdir -p "$dir"
  start="$(date +%s)"
  out="$(YUKTA_LOOP_DRAW_ID="$id" "${YUKTA_LOOP_RUNNER:-run_method}" "$n" "$method" "$dir" 2>"$dir/run.err" || true)"
  end="$(date +%s)"
  main="$(usage_of "$dir/main.json")"
  sub='{"cost_usd":0,"attempts":0,"fixed":0}'
  [[ "$method" == A ]] || sub="$(usage_sub "$REPO_ROOT/.yukta-dev/issue-$n/implement.json" "$(dirname "$REPO_ROOT")/yukta-issue-$n/.yukta-dev/heal/check")"
  res="$(grep -o 'RESULT status=[A-Z]*' <<<"$out" | tail -1 || true)"
  ledger_append "$(jq -nc --arg id "$id" --argjson n "$n" --arg method "$method" --arg res "${res:-}" --arg pr "$(grep -o 'https://github.com/[^ ]*/pull/[0-9]*' <<<"$out" | tail -1 || true)" \
    --argjson wall "$((end - start))" --argjson main "$main" --argjson sub "$sub" --arg t "$(date -u +%FT%TZ)" \
    '{type: "outcome", draw: $id, time: $t, issue: $n, method: $method, result: $res, pr_url: $pr, wall_s: $wall, main: $main, sub: $sub,
      cost_usd: (($main.cost_usd // 0) + ($sub.cost_usd // 0)), analyzer_cost_usd: 0}')"
  echo "$out" | tail -3
  [[ ! -x "$here/analyze.sh" ]] || "$here/analyze.sh" "$id" || true
}

# next: the issues assigned to you that are Ready on the project, by priority then number, with their size
cmd_next() {
  local ready list
  ready="$("$gh" project item-list 5 --owner infenia --limit 700 --format json -q '.items[] | select(.status=="Ready") | [.content.number, (.priority // "P9")] | @tsv')"
  list="$("$gh" issue list -R "$gh_repo" --assignee @me --state open --limit 200 --json number,title,body,labels)"
  printf '%-7s %-4s %-5s %s\n' issue prio size title
  while IFS=$'\t' read -r n prio; do
    row="$(jq -c --argjson n "$n" '.[] | select(.number == $n)' <<<"$list")"
    [[ -n "$row" ]] || continue
    printf '#%-6s %-4s %-5s %s\n' "$n" "$prio" "$("$here/issue-size.sh" - <<<"$row" | jq -r .size)" "$(jq -r '.title[0:80]' <<<"$row")"
  done < <(sort -t$'\t' -k2,2 -k1,1n <<<"$ready")
}

cmd_total() {
  printf '$%s of $%s\n' "$(spent_total)" "$BUDGET_TOTAL"
}

cmd_ledger() {
  ledger_all | jq -r 'if .type == "draw" then "draw    #\(.issue) size=\(.size) method=\(.method)\(if .forced then " (forced)" else "" end) loop=\(.loop_sha)\(if .loop_changing then " loop-changing" else "" end) block=\(.block).\(.position)"
    else "outcome #\(.issue) method=\(.method) \(.result) $\(.cost_usd) wall=\(.wall_s)s" end'
}

case "${1:-}" in
  plan) shift; cmd_plan "$@" ;;
  pick) shift; cmd_pick "$@" ;;
  next) cmd_next ;;
  total) cmd_total ;;
  ledger) cmd_ledger ;;
  *) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; exit 2 ;;
esac
