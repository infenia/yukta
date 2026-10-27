#!/usr/bin/env bash
# The script steps of heal and attempt (workflows heal, fix-from and attempt in session.json, see scripts/gen-heal.py). Each is run
# by a PROCESS_EXECUTOR node; they are usable by hand too.
#
#   brief           heal: stdin {stdout, ...} (the failed stage's output) or a tool result {results: [{stdout}]}; prints
#                   {brief, problems, triage}: the short text the triage model reads and the problem lines the fixer reads
#   triage-log      heal: a side branch of every port of the triage: writes the triage event (route, label, probability, seconds)
#   record-auto     heal: the free fix made the stage pass: writes the record behind healed=<stage>:auto
#   healed          check: exit 0 when anything was healed (so verify runs), else 1
#   prepare         attempt: stdin {problems, previous}; numbers the attempt, snapshots the tree, prints {prompt, attempt};
#                   exit 4 when the model is not in YUKTA_MODELS
#   claimed         attempt: stdin = the claude workflow's result; keeps its cost, usage and claim
#   judge           attempt: the guard. Decides from what changed on disk, not from what Claude said; exit 0 = a clean change
#   report          attempt: stdin = the last node's result; writes the attempt's record and prints
#                   {stage, model, effort, attempt, fixed, cost, summary, problems}; always exit 0 (the workflow branches on fixed)
#
# Environment: YUKTA_HEAL_DIR (the records of the run, required), YUKTA_STAGE, YUKTA_MODEL, YUKTA_EFFORT, YUKTA_MODELS, YUKTA_MODULE,
# YUKTA_COMMAND (the command that proves the stage), YUKTA_PROBLEMS, YUKTA_TRIAGE, YUKTA_HEAL_TIMEOUT, YUKTA_HEAL_EVENTS (a file;
# every step appends one JSON line).
# A value of "-" means empty: a start variable of a workflow cannot be.
# Nothing here commits or reverts.
set -uo pipefail

action="${1:?usage: yukta-heal.sh brief|record-auto|healed|prepare|claimed|judge|report}"
dir="${YUKTA_HEAL_DIR:?YUKTA_HEAL_DIR is required}"
for v in YUKTA_STAGE YUKTA_MODEL YUKTA_EFFORT YUKTA_TRIAGE_ROUTE YUKTA_TRIAGE_START YUKTA_TRIAGE_MODEL YUKTA_MODELS YUKTA_MODULE YUKTA_COMMAND YUKTA_PROBLEMS YUKTA_TRIAGE YUKTA_HEAL_TIMEOUT \
  YUKTA_HEAL_EVENTS YUKTA_BUDGET_CHECK; do
  [[ "${!v:-}" == - ]] && unset "$v"
done
stage="${YUKTA_STAGE:-}"
model="${YUKTA_MODEL:-}"
effort="${YUKTA_EFFORT:-}"
module="${YUKTA_MODULE:-}"
mkdir -p "$dir"
t_start="$(date +%s.%N)"

# Paths a fixer must not touch: the rules it is being measured against, the build and this tooling.
protected='^(config/|build-logic/|gradle/|\.github/|\.claude/|examples/11-dogfood/|REUSE\.toml|.*\.gradle\.kts$)'
# Ways to make a check pass without fixing the code.
suppression='SuppressFBWarnings|NOPMD|CHECKSTYLE:OFF|@SuppressWarnings\("(PMD|checkstyle)|@Disabled|spotless:off'

secs_since() { awk -v a="$1" -v b="$(date +%s.%N)" 'BEGIN { printf "%.1f", b - a }'; }

# emit EVENT JSON-OBJECT: one line on the monitoring log. Never fails a step: monitoring must not break a heal.
emit() {
  local events="${YUKTA_HEAL_EVENTS:-}"
  [[ -n "$events" ]] || return 0
  {
    mkdir -p "$(dirname "$events")"
    jq -nc --arg ev "$1" --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg session "${YUKTA_SESSION_ID:--}" \
      --arg project "$(basename "$(git rev-parse --show-toplevel 2>/dev/null || pwd)")" --argjson f "${2:-{\}}" \
      '{v: 1, ts: $ts, run: "-", session: $session, project: $project, event: $ev} + $f' \
      | { flock 9; cat >>"$events"; } 9>>"$events.lock"
  } 2>/dev/null || true
}

# excerpts PROBLEMS: up to 6 file:line spots named in the problem lines, 6 lines either side, so a cheap model needs fewer reads. Only tracked files.
excerpts() {
  local spec f l shown=0
  while IFS=: read -r f l; do
    [[ -n "$f" && -f "$f" ]] && git ls-files --error-unmatch -- "$f" >/dev/null 2>&1 || continue
    printf '%s:%s\n' "$f" "$l"
    awk -v a="$((l > 6 ? l - 6 : 1))" -v b="$((l + 6))" 'NR >= a && NR <= b { printf "%5d  %s\n", NR, substr($0, 1, 200) }' "$f"
    shown=$((shown + 1))
    ((shown >= 6)) && break
  done < <(grep -oE '[A-Za-z0-9_./-]+\.(java|go|ts|tsx|js|kts|sh|py|md|mdx|json):[0-9]+' <<<"$1" | sed -E 's#^(/[^:]*/)?##' | awk '!seen[$0]++' | head -20)
}

# snapshot: "<hash> <path>" for every modified or untracked file, to tell what a step changed.
snapshot() {
  git ls-files -m -o --exclude-standard -z | while IFS= read -r -d '' f; do
    printf '%s %s\n' "$(git hash-object -- "$f" 2>/dev/null || echo deleted)" "$f"
  done | sort
}
# changed_since FILE: paths whose state differs from the snapshot in FILE.
changed_since() { diff <(cat "$1") <(snapshot) | sed -n 's/^[<>] [^ ]* //p' | sort -u; }
files_json() { if [[ -n "$1" ]]; then printf '%s\n' "$1" | jq -R . | jq -sc .; else echo '[]'; fi; }

# plain: spinner and colour codes out (mint redraws one line many times), carriage returns as newlines
plain() { sed -E 's/\x1b\[[0-9;?]*[A-Za-z]//g; s/\r/\n/g'; }

# ecosystem STAGE: what the stage checks, from its name (plan.sh names the stages), so the triage question can stay neutral
ecosystem() {
  case "$1" in
    cli_*) echo "go (cli)" ;;
    ui_*) echo "typescript (ui)" ;;
    reuse | actionlint | docs) echo "repo files" ;;
    *) echo "java (gradle)" ;;
  esac
}

# problem_lines FILE [MAX]: the lines of a run that say what went wrong, as yukta's --quiet picks them.
problem_lines() {
  plain <"$1" | awk '
    /What went wrong/ { ctx = 3; print; next }
    ctx > 0 && /^\* / { ctx = 0; next }
    ctx > 0 { ctx--; print; next }
    tolower($0) ~ /(^|[^a-z])(error|failed|failure)([^a-z]|$)|exception|see the report at|violation/ { print }
  ' | cut -c1-300 | awk '!seen[$0]++' | head -"${2:-60}"
}

# failing_tests: test classes with a failure in a recent report.
failing_tests() {
  find . -path '*/build/test-results/*' -name 'TEST-*.xml' -mmin -120 -print0 2>/dev/null \
    | xargs -0 -r grep -lE '<(failure|error) ' | sed -E 's#.*/TEST-(.*)\.xml#\1#' | sort -u | head -15
}

# the stdout of a failed stage out of a message: a tool result {results: [{stdout}]}
stdout_of() { jq -r '(.results[0].stdout // .stdout // "")'; }

# attempt_no: the number of the attempt that prepare started (kept in the records)
attempt_no() { cat "$dir/$stage.attempt" 2>/dev/null || echo 0; }

# record FIXED FILES-JSON COST SUMMARY [USAGE-JSON]: <dir>/<stage>-<n>.json, what healed= and the next attempt read
record() {
  local n summary="${4//$'\n'/ }"
  n="$(attempt_no)"
  summary="${summary:0:160}"
  jq -n --arg stage "$stage" --argjson attempt "$n" --arg mode "$model" --arg effort "$effort" --argjson fixed "$1" \
    --argjson files "$2" --arg cost "$3" --arg summary "$summary" --argjson usage "${5:-null}" \
    '{stage: $stage, attempt: $attempt, mode: $mode, effort: (if $effort == "" then null else $effort end),
      fixed: $fixed, files: $files, cost: ($cost | tonumber), summary: $summary, usage: $usage}' >"$dir/$stage-$n.json"
  printf 'HEAL %s attempt=%s mode=%s%s fixed=%s files=%s cost=$%s summary=%s\n' \
    "$stage" "$n" "$model" "${effort:+ effort=$effort}" "$1" "$(jq 'length' <<<"$2")" "$3" "${summary:0:120}" >&2
}

case "$action" in
  brief)
    msg="$(cat)"
    out="$(mktemp)"
    jq -r '(.stdout // .results[0].stdout // "")' <<<"$msg" >"$out"
    # the triage state: what the failure says, the lines a human would read first, and nothing else (measured on 54 real failures,
    # eval/triage); System One reads about 50 tokens a second and Ollama cuts a call off at 60 seconds: 1000 characters take about 7
    brief="$(
      {
        echo "stage: $stage"
        echo "ecosystem: $(ecosystem "$stage")"
        echo "files changed so far: $(git ls-files -m -o --exclude-standard | wc -l)"
        echo "command: ${YUKTA_COMMAND:-}"
        problem_lines "$out" 25
        [[ "$stage" == tests ]] && echo "failing test classes: $(failing_tests | paste -sd' ')"
      } | cut -c1-300 | head -c 1000
    )"
    # the keyword lines do not say it for every tool (reuse lists the files without a licence): the end of the output too
    problems="$(
      problem_lines "$out" 60
      echo "Output (last 40 lines):"
      plain <"$out" | tail -n 40 | cut -c1-300
      [[ "$stage" == tests ]] && echo "failing test classes: $(failing_tests | paste -sd' ')"
    )"
    rm -f "$out"
    echo "$t_start" >"$dir/$stage.triage-t0"
    emit brief "$(jq -nc --arg stage "$stage" --argjson chars "${#brief}" --arg secs "$(secs_since "$t_start")" '{stage: $stage, chars: $chars, secs: ($secs | tonumber)}')"
    jq -nc --arg brief "$brief" --arg problems "$problems" --arg triage "${YUKTA_TRIAGE:-on}" '{brief: $brief, problems: $problems, triage: $triage}'
    ;;

  triage-log)
    # stdin: the message the triage routed. The classification is in its metadata; where a step sees it is not documented, so look
    # in the places it can be and keep the raw text of the first 300 characters for the report when none has it.
    msg="$(cat)"
    t0="$(cat "$dir/$stage.triage-t0" 2>/dev/null || echo "$t_start")"
    emit triage "$(jq -nc --arg stage "$stage" --arg route "${YUKTA_TRIAGE_ROUTE:-}" --arg start "${YUKTA_TRIAGE_START:-}" --arg model "${YUKTA_TRIAGE_MODEL:-}" \
      --arg secs "$(secs_since "$t0")" --arg raw "${msg:0:300}" --argjson m "$(jq -c '.' <<<"$msg" 2>/dev/null || echo null)" '
      ($m | (.metadata.classification // .classification // .results[0].metadata.classification // {})) as $c
      | {stage: $stage, route: (if $route == "error" or $route == "uncertain" then $route else "level" end), label: ($c.label // (if $route == "error" or $route == "uncertain" then null else $route end)),
         p: ($c.probability // null), start: $start, model: $model, secs: ($secs | tonumber), chars: (($m.brief // "") | length),
         fallback: (if $route == "error" or $route == "uncertain" then $route else null end), classification_seen: ($c != {}), raw: (if $c == {} then $raw else null end)}')"
    ;;

  record-auto)
    n=$(($(ls "$dir/$stage"-[0-9]*.json 2>/dev/null | wc -l) + 1))
    echo "$n" >"$dir/$stage.attempt"
    model=auto effort=""
    record true '[]' 0 "the free automatic fix made the stage pass"
    ;;

  healed)
    ls "$dir"/*-[0-9]*.json >/dev/null 2>&1 && jq -e -s 'any(.[]; .fixed)' "$dir"/*-[0-9]*.json >/dev/null
    ;;

  prepare)
    msg="$(cat)"
    n=$(($(ls "$dir/$stage"-[0-9]*.json 2>/dev/null | wc -l) + 1))
    echo "$n" >"$dir/$stage.attempt"
    # a model that is left out (yk check --models) still has its rungs; the attempt stops here and costs nothing
    if [[ -n "${YUKTA_MODELS:-}" && ",${YUKTA_MODELS}," != *",$model,"* ]]; then
      echo "model $model is not allowed in this run (--models $YUKTA_MODELS)" >&2
      exit 4
    fi
    # the budget of the whole check: what the attempts of every stage have cost so far; at the cap no rung starts (the ladder ends, nothing is spent)
    if [[ -n "${YUKTA_BUDGET_CHECK:-}" ]] && ls "$dir"/*-[0-9]*.json >/dev/null 2>&1; then
      spent="$(jq -s 'map(.cost // 0) | add // 0' "$dir"/*-[0-9]*.json)"
      if awk -v s="$spent" -v c="$YUKTA_BUDGET_CHECK" 'BEGIN { exit !(s >= c) }'; then
        echo "the budget of this check is spent: \$$spent of \$$YUKTA_BUDGET_CHECK (YUKTA_BUDGET_CHECK)" >&2
        exit 4
      fi
    fi
    snapshot >"$dir/$stage-$n.before"
    # the working tree as it is now (the developer's own edits included), to diff this attempt against
    base="$(git stash create 2>/dev/null)"
    echo "${base:-HEAD}" >"$dir/$stage-$n.base"
    problems="$(jq -r '.problems // ""' <<<"$msg")"
    previous="$(jq -r '.previous // ""' <<<"$msg")"
    stat="$(git diff --stat | tail -8)"
    # everything that varies per run is in the user prompt; the system prompt is one constant file, so every attempt shares the cached prefix
    excerpts="$(excerpts "$problems")"
    prompt="Stage \"$stage\" of the Yukta quality gate failed. Fix the root cause in the source so it passes.
Problems (from the run):
$problems
${excerpts:+The code around the failures (file:line, a few lines either side):
$excerpts
}${previous:+Earlier attempt: $previous
}Files already changed in the working tree (by the developer, keep them):
${stat:-none}
Verify with only:
${YUKTA_COMMAND:-the failing command}
${YUKTA_HEAL_TIMEOUT:+You have about $((YUKTA_HEAL_TIMEOUT / 60)) minutes at ${effort:-this} effort. If you cannot fix it in that time, stop and answer fixed=false with what you learned.
}Then answer with the JSON object only."
    jq -nc --arg prompt "$prompt" --argjson attempt "$n" '{prompt: $prompt, attempt: $attempt}'
    ;;

  claimed)
    n="$(attempt_no)"
    msg="$(cat)"
    # the claude workflow's result: {results: [{output: <claude's JSON>}]}
    jq -c '(.results[0].output // .output // {}) as $r
      | {cost: ($r.total_cost_usd // 0),
         usage: ($r.usage | if . == null then null else {input: .input_tokens, cache_creation: .cache_creation_input_tokens, cache_read: .cache_read_input_tokens, output: .output_tokens} end),
         answer: ($r.structured_output // (($r.result // "") | fromjson? ) // {})}
      | {cost, usage, claimed: (.answer.fixed // false),
         summary: (.answer.summary // ("no answer from claude" + (if $r.is_error == true or ($r.subtype // "success") != "success" then " (\($r.subtype // "error"): \(($r.result // "")[0:100]))" else "" end)))}' <<<"$msg" >"$dir/$stage-$n.claude" \
      || echo '{"cost":0,"usage":null,"claimed":false,"summary":"no answer from claude"}' >"$dir/$stage-$n.claude"
    # the live line of the attempt (the recheck decides whether it fixed the stage; healed= in RESULT says so)
    jq -r --arg l "HEAL $stage attempt=$n mode=$model effort=$effort" '"\($l) claimed=\(.claimed) cost=$\(.cost) summary=\(.summary[0:120])"' "$dir/$stage-$n.claude" >&2
    ;;

  judge)
    n="$(attempt_no)"
    claimed="$(jq -r '.claimed' "$dir/$stage-$n.claude" 2>/dev/null || echo false)"
    changed="$(changed_since "$dir/$stage-$n.before")"
    base="$(cat "$dir/$stage-$n.base" 2>/dev/null || echo HEAD)"
    violations=""
    touched="$(grep -E "$protected" <<<"$changed")"
    [[ -z "$touched" ]] || violations+=" touched protected path: $(head -3 <<<"$touched" | paste -sd,)."
    diffs="$(while IFS= read -r f; do [[ -n "$f" && -f "$f" ]] && git diff -U0 "$base" -- "$f" 2>/dev/null; done <<<"$changed")"
    untracked="$(git ls-files -o --exclude-standard | grep -F -x -f <(printf '%s\n' "$changed") | head -50)"
    # into a variable first: grep -q on a pipe ends early and, under pipefail, the writer's SIGPIPE would hide the match
    added_text="$({ grep -E '^\+[^+]' <<<"$diffs"; [[ -n "$untracked" ]] && xargs -d '\n' cat <<<"$untracked"; } 2>/dev/null)"
    if grep -Eq "$suppression" <<<"$added_text"; then
      violations+=" added a suppression."
    fi
    removed="$(grep -cE '^-[^-].*(assert|@Test|verify\()' <<<"$diffs")"
    added="$(grep -cE '^\+[^+].*(assert|@Test|verify\()' <<<"$diffs")"
    ((removed > added)) && violations+=" removed assertions ($removed removed, $added added)."
    # a test whose assertion was edited, with no code changed beside it, is a test made to fit the code
    tests_re='(/src/test/|Test\.java$|_test\.go$|\.(test|spec)\.[jt]sx?$)'
    if grep -Eq "$tests_re" <<<"$changed" && ! grep -Ev "$tests_re" <<<"$changed" | grep -q . \
      && [[ "$removed" -gt 0 && "$added" -gt 0 ]]; then
      violations+=" changed test expectations without changing any code."
    fi
    [[ "$claimed" == true && -z "$changed" ]] && violations+=" claimed a fix but changed nothing."
    jq -nc --argjson files "$(files_json "$changed")" --arg violations "$violations" '{files: $files, violations: $violations}' >"$dir/$stage-$n.verdict"
    # 0 = a clean change that Claude says fixes it: the stage runs again
    [[ "$claimed" == true && -z "$violations" && -n "$changed" ]]
    ;;

  report)
    n="$(attempt_no)"
    msg="$(cat)"
    claude="$dir/$stage-$n.claude"
    verdict="$dir/$stage-$n.verdict"
    cost="$(jq -r '.cost // 0' "$claude" 2>/dev/null || echo 0)"
    usage="$(jq -c '.usage' "$claude" 2>/dev/null || echo null)"
    summary="$(jq -r '.summary // ""' "$claude" 2>/dev/null)"
    [[ -n "$summary" ]] || summary="the attempt did not start (model not allowed, or the issue budget is spent)"
    files="$(jq -c '.files // []' "$verdict" 2>/dev/null || echo '[]')"
    guard="$(jq -r '.violations // ""' "$verdict" 2>/dev/null)"
    # the recheck ran when the message is a tool result labelled with this stage: it passed when its command exited 0
    ran="$(jq -r --arg s "$stage" '((.results[0].input.label // "") == $s)' <<<"$msg")"
    ok="$(jq -r '(.results[0].success // false)' <<<"$msg")"
    problems="${YUKTA_PROBLEMS:-}"
    if [[ "$ran" == true && "$ok" != true ]]; then
      tmp="$(mktemp)"; stdout_of <<<"$msg" >"$tmp"
      problems="$(problem_lines "$tmp" 60)"$'\n'"Output (last 40 lines):"$'\n'"$(plain <"$tmp" | tail -n 40 | cut -c1-300)"
      rm -f "$tmp"
    fi
    fixed=false
    [[ "$ran" == true && "$ok" == true ]] && fixed=true
    [[ -n "$guard" ]] && summary="GUARD:$guard $summary"
    # a step with outputFormat JSON must print nothing but the JSON (the engine merges stderr into stdout): no HEAL line here, claimed prints it
    record "$fixed" "$files" "$cost" "$summary" "$usage" 2>/dev/null
    emit fix "$(jq -nc --arg stage "$stage" --argjson attempt "$n" --arg model "$model" --arg effort "$effort" --argjson fixed "$fixed" \
      --argjson files "$files" --arg cost "$cost" --argjson usage "$usage" --arg secs "$(secs_since "$t_start")" --arg guard "$guard" --arg summary "${summary:0:160}" \
      --arg modules "$module" \
      '{stage: $stage, attempt: $attempt, model: $model, effort: (if $effort == "" then null else $effort end), fixed: $fixed, files: $files,
        cost: ($cost | tonumber), usage: $usage, secs: ($secs | tonumber), guard: (if $guard == "" then null else $guard end), summary: $summary,
        modules: ($modules | split(" ") | map(select(. != "")))}')"
    # what the next attempt reads as "Earlier attempt": the summary plus the files this one changed, so it does not explore them again
    handoff="${summary:0:300}"
    [[ "$files" == '[]' ]] || handoff+=" Files it changed (still in the working tree): $(jq -r 'join(", ")' <<<"$files" | cut -c1-300)."
    jq -nc --arg stage "$stage" --arg model "$model" --arg effort "$effort" --argjson attempt "$n" --argjson fixed "$fixed" --arg cost "$cost" \
      --arg summary "$handoff" --arg problems "$problems" --arg files "$files" \
      '{stage: $stage, model: $model, effort: $effort, attempt: $attempt, fixed: $fixed, changed: ($files != "[]"), cost: ($cost | tonumber), summary: $summary,
        problems: (if $fixed then "" else $problems end)}'
    # always 0: a step that exits non-zero has no parsed `output`, and the carry of the next attempt reads it. The workflow branches on fixed.
    exit 0
    ;;

  *) echo "yukta-heal.sh: unknown action '$action'" >&2; exit 2 ;;
esac
