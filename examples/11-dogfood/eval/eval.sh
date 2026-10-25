#!/usr/bin/env bash
# Token-efficiency evaluation of the Yukta dev loop on one issue (see README.md in this directory).
#
#   eval.sh fixture N       save issue N as eval/issues/N/issue.json (the stub gh serves it)
#   eval.sh run A|C N       one run of an arm (prep, claude, collect, grade); N is the repetition
#   eval.sh validate        check the grader of EVAL_ISSUE: it must fail on the base commit and pass with the reference fix
#   eval.sh collect A|C N   collect and grade a run again from its files, without running Claude
#   eval.sh grade A|C N     only grade a run
#   eval.sh report          the comparison table of every run that has a result.json
#
# Arm A: one Claude Code session fixes the issue itself; Yukta is only its check (yk gradle / yk check --no-heal).
# Arm C: the same Claude Code session is an orchestrator: it starts `yk run issue`, whose slim headless implementer
#        (with a code-navigation index; the backend that ran is recorded) and fixers (chosen by a triage; fresh
#        sessions) do the work, and it reports the RESULT line. (Arm B, C without the index and with fixers that
#        resumed the implementer, is retired; its runs under runs/B* of old results still collect and grade.)
# Both main sessions start the same way (same model, tools and the user's own Claude Code setup), so the
# difference is who does the work. Sub-flow tokens are counted in arm C's total. Every run is isolated:
# a fresh clone of the base commit with a bare origin, a stub gh and project status (the real issue stays
# untouched, no PR opens), and a grading test applied after the run.
#
# Environment: EVAL_ISSUE (1207), EVAL_DIR (runs go here, default <checkout>/.yukta-dev/eval),
#   EVAL_ISSUES_DIR (where issues/<n>/ live, default eval/issues; tests use a temporary one), EVAL_SRC (repository to clone, default this checkout), EVAL_BASE (commit to start from, default
#   origin/main of EVAL_SRC, fetched), EVAL_YK (the yk to use, default ../yk), EVAL_CLAUDE (claude),
#   EVAL_MODEL (opus), EVAL_BUDGET (USD per run and per sub-flow session, 5), EVAL_MAIN_TURNS (turn cap of the
#   main session, 60). The dev server must be running (yk up) and built from the code under test.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$here" rev-parse --show-toplevel)"
EVAL_ISSUE="${EVAL_ISSUE:-1207}"
EVAL_DIR="${EVAL_DIR:-${YUKTA_DEV_STATE_DIR:-$repo_root/.yukta-dev}/eval}"
EVAL_SRC="${EVAL_SRC:-$repo_root}"
EVAL_YK="${EVAL_YK:-$here/../yk}"
EVAL_CLAUDE="${EVAL_CLAUDE:-claude}"
EVAL_MODEL="${EVAL_MODEL:-opus}"
EVAL_BUDGET="${EVAL_BUDGET:-5}"
EVAL_MAIN_TURNS="${EVAL_MAIN_TURNS:-60}"
issue_dir="${EVAL_ISSUES_DIR:-$here/issues}/$EVAL_ISSUE"
die() { echo "eval: $*" >&2; exit 1; }
command -v jq >/dev/null || die "jq is required"

# the issue's grading settings: MODULE, HIDDEN_DEST, HIDDEN_CMD, SUITE_CMD, optional HIDDEN_SRC (see issues/1207/grade.env)
load_issue() {
  [[ -f "$issue_dir/grade.env" && -f "$issue_dir/issue.json" ]] \
    || die "issue $EVAL_ISSUE is not set up: $issue_dir needs issue.json, grade.env and the grading test (README.md)"
  # shellcheck source=/dev/null
  source "$issue_dir/grade.env"
  HIDDEN_SRC="${HIDDEN_SRC:-hidden_test.go}"
  [[ -f "$issue_dir/$HIDDEN_SRC" ]] || die "the grading test $issue_dir/$HIDDEN_SRC is missing (HIDDEN_SRC in grade.env)"
  : "${MODULE:?grade.env sets MODULE}" "${HIDDEN_DEST:?grade.env sets HIDDEN_DEST}" "${HIDDEN_CMD:?grade.env sets HIDDEN_CMD}" "${SUITE_CMD:?grade.env sets SUITE_CMD}"
}

# prep DIR: a fresh run directory with a clone of the base commit, and the stubs
prep() {
  local d="$1" base
  [[ -n "${EVAL_BASE:-}" ]] || { git -C "$EVAL_SRC" fetch -q origin main; EVAL_BASE="$(git -C "$EVAL_SRC" rev-parse origin/main)"; }
  base="$EVAL_BASE"
  rm -rf "$d"
  mkdir -p "$d/stubs"
  git clone -q --bare "$EVAL_SRC" "$d/origin.git"
  git -C "$d/origin.git" update-ref refs/heads/main "$base"
  git clone -q "$d/origin.git" "$d/yukta"
  git -C "$d/yukta" config user.email eval@example.invalid
  git -C "$d/yukta" config user.name eval
  echo "$base" >"$d/base"
  cp "$issue_dir/issue.json" "$d/stubs/issue.json"
  cat >"$d/stubs/gh" <<'SH'
#!/usr/bin/env bash
# stub gh: serves the saved issue, logs every call; writes (pr create, project) go nowhere
here="$(cd "$(dirname "$0")" && pwd)"
echo "$*" >>"$here/gh.log"
q="" args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do [[ "${args[i]}" == -q ]] && q="${args[i + 1]}"; done
case "$1 $2" in
  "api user") echo tester; exit 0 ;;
  "auth token") echo tok; exit 0 ;;
  "pr create") echo "https://example.invalid/pull/1"; exit 0 ;;
  "issue view") out="$(cat "$here/issue.json")" ;;
  "project item-list") out="$(jq -c '{items: [{content: {number: .number}, status: "Ready"}]}' "$here/issue.json")" ;;
  *) echo "stub gh: unsupported: $*" >&2; exit 1 ;;
esac
if [[ -n "$q" ]]; then jq -r "$q" <<<"$out"; else echo "$out"; fi
SH
  cat >"$d/stubs/project-status" <<'SH'
#!/usr/bin/env bash
echo "$1 $2" >>"$(cd "$(dirname "$0")" && pwd)/project-status.log"
SH
  chmod +x "$d/stubs/gh" "$d/stubs/project-status"
}

brief() { # the issue as the implementer's brief shows it
  jq -r '"# Issue #\(.number): \(.title)\n\n\(.url)\n\n\(.body)"' "$1/stubs/issue.json"
}

# one headless main session, the user's own setup, from inside the clone
main_claude() { # DIR PROMPT OUT
  local d="$1" prompt="$2" out="$3"
  (cd "$d/yukta" && env -u CLAUDECODE -u CLAUDE_CODE_SESSION_ID -u CLAUDE_CODE_ENTRYPOINT -u CLAUDE_CODE_SSE_PORT \
    YUKTA_REPO_ROOT="$d/yukta" YUKTA_GH="$d/stubs/gh" YUKTA_PROJECT_STATUS_CMD="$d/stubs/project-status" \
    YUKTA_CHECK_EXTRAS=off YUKTA_WARM_DELAY=0 YUKTA_IMPL_MODEL="$EVAL_MODEL" YUKTA_IMPL_BUDGET="$EVAL_BUDGET" \
    YUKTA_BUDGET_ISSUE="$EVAL_BUDGET" YUKTA_DOGFOOD=off \
    "$EVAL_CLAUDE" -p "$prompt" --model "$EVAL_MODEL" --no-session-persistence --permission-mode acceptEdits \
    --allowedTools "Read" "Edit" "Write" "Grep" "Glob" "Bash($EVAL_YK:*)" "Bash(go:*)" "Bash(git diff:*)" \
    "Bash(git status:*)" "Bash(git log:*)" "Bash(codegraph:*)" \
    --disallowedTools "Bash(git commit:*)" "Bash(git push:*)" "Bash(gh:*)" \
    --max-budget-usd "$EVAL_BUDGET" --max-turns "$EVAL_MAIN_TURNS" --output-format json) >"$out" 2>"$out.err" || true
}

prompt_a() {
  cat <<TXT
$(brief "$1")

You are working in $1/yukta (a clone of the repository). Fix the issue yourself: change the code, add tests, and
make the checks pass. Run Gradle only through Yukta: \`$EVAL_YK gradle <args>\` for a scope while you work, and
finish with \`$EVAL_YK check --no-heal -p $1/yukta -m $MODULE\` (Yukta runs the check and reports; it does not fix
anything, you fix failures). Do not commit or push. When the check passes, answer with one short sentence.
TXT
}

prompt_c() {
  cat <<TXT
Issue #$EVAL_ISSUE in $1/yukta must be fixed, and you are the orchestrator, not the implementer: do not edit code
yourself. Start Yukta's issue workflow:

  $EVAL_YK run issue $EVAL_ISSUE --no-pause before-implement,before-pr

Run it in the foreground with the Bash timeout set to 600000 ms. It prints a few lines and ends with a
\`RESULT status=...\` line. If the command times out before RESULT, re-attach with
\`$EVAL_YK watch <session> <execution>\` (both are in its STARTED line) the same way. If the run ends ESCALATE,
read what it says and start it once more with the same command; do not do more. Do not commit or push. Finish with
the RESULT line and one short sentence.
TXT
}

usage_of() { # a claude -p json file -> {input, cache_creation, cache_read, output, cost, turns, duration_ms}
  jq -c '{input: (.usage.input_tokens // 0), cache_creation: (.usage.cache_creation_input_tokens // 0),
          cache_read: (.usage.cache_read_input_tokens // 0), output: (.usage.output_tokens // 0),
          cost: (.total_cost_usd // 0), turns: (.num_turns // 0), duration_ms: (.duration_ms // 0)}' "$1" 2>/dev/null \
    || echo '{"input":0,"cache_creation":0,"cache_read":0,"output":0,"cost":0,"turns":0,"duration_ms":0}'
}

# sub-flow records of a run: the implementer and every heal attempt of the issue worktree.
# Records are named by attempt: a second `yk run issue` in the same worktree overwrites the first run's
# (yukta issue "Restarting an issue run ... overwrites"); cache_trace.py reads the transcripts and sees both.
sub_of() { # DIR
  local d="$1" files=()
  mapfile -t files < <(ls "$d"/yukta/.yukta-dev/issue-*/implement.json "$d"/yukta-issue-*/.yukta-dev/heal/check/*-[0-9]*.json 2>/dev/null)
  if ((${#files[@]} == 0)); then
    echo '{"input":0,"cache_creation":0,"cache_read":0,"output":0,"cost":0,"attempts":0,"fixed":0}'
    return
  fi
  jq -cs 'map({input: (.usage.input_tokens // .usage.input // 0),
               cache_creation: (.usage.cache_creation_input_tokens // (.usage.cache_creation | numbers) // 0),
               cache_read: (.usage.cache_read_input_tokens // .usage.cache_read // 0),
               output: (.usage.output_tokens // .usage.output // 0),
               cost: (.cost // 0), fixed: ((.fixed // .done // false) | if . then 1 else 0 end)})
         | {input: (map(.input) | add), cache_creation: (map(.cache_creation) | add), cache_read: (map(.cache_read) | add),
            output: (map(.output) | add), cost: (map(.cost) | add), attempts: length, fixed: (map(.fixed) | add)}' "${files[@]}"
}

# grade DIR: applies the hidden test to the code the run produced; prints a JSON object
grade() {
  local d="$1" code hidden=false suite=false changed
  code="$d/yukta"
  [[ -d "$d/yukta-issue-$EVAL_ISSUE" ]] && code="$d/yukta-issue-$EVAL_ISSUE"
  changed="$(git -C "$code" status --porcelain | wc -l | tr -d ' ')"
  cp "$issue_dir/$HIDDEN_SRC" "$code/$HIDDEN_DEST"
  (cd "$code" && bash -c "$HIDDEN_CMD" >"$d/grade-hidden.log" 2>&1) && hidden=true
  rm -f "$code/$HIDDEN_DEST"
  (cd "$code" && bash -c "$SUITE_CMD" >"$d/grade-suite.log" 2>&1) && suite=true
  jq -nc --argjson h "$hidden" --argjson s "$suite" --argjson c "$changed" --arg code "$code" \
    '{hidden_pass: $h, suite_pass: $s, changed_files: $c, graded: $code}'
}

write_result() { # ARM N DIR START END
  local arm="$1" n="$2" d="$3" main sub
  local nav loop
  nav="$(jq -c '{backend, ok, index_seconds, version}' "$(ls "$d"/yukta/.yukta-dev/issue-*/nav.json 2>/dev/null | head -1)" 2>/dev/null || echo null)"; [[ -n "$nav" ]] || nav=null
  loop="$(git -C "$here" rev-parse --short=9 HEAD 2>/dev/null || echo unknown)"; [[ -z "$(git -C "$here" status --porcelain -- "$here/.." 2>/dev/null)" ]] || loop+=-dirty
  main="$(usage_of "$d/main.json")"
  sub="$(sub_of "$d")"
  jq -nc --arg arm "$arm" --argjson n "$n" --arg base "$(cat "$d/base")" --arg model "$EVAL_MODEL" \
    --argjson wall "$(($5 - $4))" --argjson main "$main" --argjson sub "$sub" --argjson g "$(grade "$d")" --argjson nav "$nav" --arg loop "$loop" \
    --arg answer "$(jq -r '.result // ""' "$d/main.json" 2>/dev/null | tail -c 400)" \
    '{arm: $arm, run: $n, base: $base, model: $model, loop_sha: $loop, nav: $nav, wall_s: $wall, main: $main, sub: $sub, grade: $g,
      total: {input: ($main.input + $sub.input), cache_creation: ($main.cache_creation + $sub.cache_creation),
              cache_read: ($main.cache_read + $sub.cache_read), output: ($main.output + $sub.output),
              cost: ($main.cost + $sub.cost)}, answer: $answer}' >"$d/result.json"
  jq . "$d/result.json"
}

run_dir() { echo "$EVAL_DIR/runs/${1:?arm A|C}${2:?run number}"; }

cmd_run() {
  local arm="${1:?arm A|C}" n="${2:?run number}" d start end
  [[ "$arm" == B ]] && die "arm B is retired, it is part of C now (use C)"
  [[ "$arm" =~ ^[AC]$ ]] || die "arm must be A or C"
  load_issue
  d="$(run_dir "$arm" "$n")"
  prep "$d"
  echo ">> run $arm$n on base $(cut -c1-9 "$d/base"), model $EVAL_MODEL, budget \$$EVAL_BUDGET" >&2
  start="$(date +%s)"
  case "$arm" in
    A) main_claude "$d" "$(prompt_a "$d")" "$d/main.json" ;;
    C) main_claude "$d" "$(prompt_c "$d")" "$d/main.json" ;;
  esac
  end="$(date +%s)"
  write_result "$arm" "$n" "$d" "$start" "$end"
}

# validate: the grader of EVAL_ISSUE must fail on the base commit and pass with the reference fix (issues/<n>/reference-fix.patch),
# and the existing suite must pass in both. Prints a JSON verdict; exit 0 when the grader is sound.
cmd_validate() {
  load_issue
  [[ -f "$issue_dir/reference-fix.patch" ]] || die "$issue_dir/reference-fix.patch is missing: a grader is validated against a known fix"
  local d="$EVAL_DIR/validate" before after
  prep "$d"
  before="$(grade "$d")"
  git -C "$d/yukta" apply "$issue_dir/reference-fix.patch" || die "the reference fix does not apply to the base commit $(cut -c1-9 "$d/base")"
  after="$(grade "$d")"
  jq -n --argjson b "$before" --argjson a "$after" --arg base "$(cut -c1-9 "$d/base")" \
    '{base: $base, on_base: {hidden_pass: $b.hidden_pass, suite_pass: $b.suite_pass}, with_fix: {hidden_pass: $a.hidden_pass, suite_pass: $a.suite_pass},
      sound: ($b.hidden_pass == false and $b.suite_pass == true and $a.hidden_pass == true and $a.suite_pass == true)}' | tee "$d/validate.json"
  jq -e .sound "$d/validate.json" >/dev/null || { echo "the grader is not sound: it must FAIL on the base commit and PASS with the fix, the suite passing in both" >&2; exit 1; }
}

cmd_report() {
  local files=("$EVAL_DIR"/runs/*/result.json)
  [[ -e "${files[0]}" ]] || die "no results yet in $EVAL_DIR/runs"
  jq -rs '
    def med: sort | if length == 0 then 0 else (.[(length - 1) / 2 | floor] + .[length / 2 | floor]) / 2 end;
    def tok: .total.input + .total.cache_creation + .total.cache_read + .total.output;
    def mtok: .main.input + .main.cache_creation + .main.cache_read + .main.output;
    def stok: .sub.input + .sub.cache_creation + .sub.cache_read + .sub.output;
    def cr: (.total.cache_read * 100 / ([tok, 1] | max) | floor);
    def r2: . * 100 | round / 100;
    "## Per run\n",
    "| run | model | cost | tokens | main tokens | sub-flow tokens | cache-read | turns | attempts | wall | hidden test | suite |",
    "|---|---|---|---|---|---|---|---|---|---|---|---|",
    (sort_by(.arm, .run)[] |
      "| \(.arm)\(.run) | \(.model) | $\(.total.cost | r2) | \(tok) | \(mtok) | \(stok) | \(cr)% | \(.main.turns) | \(.sub.attempts) | \(.wall_s)s | \(if .grade.hidden_pass then "pass" else "FAIL" end) | \(if .grade.suite_pass then "pass" else "FAIL" end) |"),
    "\n## Per arm (median, [min..max])\n",
    "| arm | runs | correct | cost | tokens | main tokens | sub-flow tokens | wall |",
    "|---|---|---|---|---|---|---|---|",
    (group_by(.arm)[] | . as $g | $g[0].arm as $a |
      "| \($a) | \($g | length) | \($g | map(select(.grade.hidden_pass and .grade.suite_pass)) | length)/\($g | length) | $\($g | map(.total.cost) | med | r2) [\($g | map(.total.cost) | min | r2)..\($g | map(.total.cost) | max | r2)] | \($g | map(tok) | med | floor) [\($g | map(tok) | min)..\($g | map(tok) | max)] | \($g | map(mtok) | med | floor) | \($g | map(stok) | med | floor) | \($g | map(.wall_s) | med | floor)s |")
  ' "${files[@]}"
  echo
  echo "Note: 'hidden test' and 'suite' grade the code, not the delivery. For arm C check the workflow's own"
  echo "RESULT line in each run's result.json (answer): an ESCALATE run can hold correct code and deliver nothing."
}

case "${1:-}" in
  fixture)
    n="${2:-$EVAL_ISSUE}"
    mkdir -p "${EVAL_ISSUES_DIR:-$here/issues}/$n"
    gh issue view "$n" -R "${YUKTA_ISSUES_REPO:-infenia/yukta}" --json number,title,url,state,body \
      | jq '. + {assignees: [{login: "tester"}]}' >"${EVAL_ISSUES_DIR:-$here/issues}/$n/issue.json"
    echo "saved ${EVAL_ISSUES_DIR:-$here/issues}/$n/issue.json; add grade.env and hidden_test.go next (README.md)" ;;
  run) shift; cmd_run "$@" ;;
  collect)
    load_issue
    d="$(run_dir "${2:-}" "${3:-}")"  # again, from a run's files, without running Claude
    write_result "$2" "$3" "$d" "$(stat -c %Y "$d/base")" "$(stat -c %Y "$d/main.json")" ;;
  grade) load_issue; grade "$(run_dir "${2:-}" "${3:-}")" ;;
  report) cmd_report ;;
  validate) cmd_validate ;;
  *) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; exit 2 ;;
esac
