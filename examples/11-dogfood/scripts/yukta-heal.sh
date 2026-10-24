#!/usr/bin/env bash
# The steps of the self-healing dogfood check (see heal_workflow in _lib.sh). Each is run by a Yukta
# PROCESS_EXECUTOR node of the `check` workflow, but usable by hand.
#
#   yukta-heal.sh run <stage> -- <command...>   run a stage's command, keep its output and exit code
#   yukta-heal.sh fix <stage> <n> <mode>        one repair attempt; mode auto or a model (haiku, sonnet, opus)
#   yukta-heal.sh rerun analyzers               one gradle run (plus the other tools) of the analyzers still failing
#   yukta-heal.sh verify                        re-run every stage's tasks, only when something was healed
#
# Environment: YUKTA_HEAL_DIR (records, required), YUKTA_HEAL_TASKS (the gradle tasks of the stage; for
# mode auto the fix tasks), YUKTA_MODULE (modules the run is scoped to, space separated),
# YUKTA_HEAL_ANALYZERS (name=command lines of the healable analyzers), YUKTA_HEAL_BUDGET (USD per
# attempt, default 1).
#
# fix exits non-zero when it did not repair the stage (the workflow routes on that); it never commits
# and never reverts, and marks the attempt as not fixed when it touched protected paths, added a
# suppression or removed assertions. It prints one line
#   HEAL <stage> attempt=<n> mode=<m> fixed=<true|false> files=<n> cost=$<x> summary=<text>
# and writes <YUKTA_HEAL_DIR>/<stage>-<n>.json, with the token usage of the Claude session.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
action="${1:?usage: yukta-heal.sh run|fix|rerun|verify ...}"
dir="${YUKTA_HEAL_DIR:?YUKTA_HEAL_DIR is required}"
tasks="${YUKTA_HEAL_TASKS:-}"
module="${YUKTA_MODULE:-}"
budget="${YUKTA_HEAL_BUDGET:-1}"
mkdir -p "$dir"

# Paths a fixer must not touch: the rules it is being measured against, the build and this tooling.
protected='^(config/|build-logic/|gradle/|\.github/|\.claude/|examples/11-dogfood/|REUSE\.toml|.*\.gradle\.kts$)'
# Ways to make a check pass without fixing the code.
suppression='SuppressFBWarnings|NOPMD|CHECKSTYLE:OFF|@SuppressWarnings\("(PMD|checkstyle)|@Disabled|spotless:off'

gradle() { ./gradlew "$@" -q --console=plain; }

# ---- run: a stage's command with its output and exit code kept -------------------------------------

if [[ "$action" == run ]]; then
  stage="${2:?stage}"
  [[ "${3:-}" == -- ]] || { echo "usage: yukta-heal.sh run <stage> -- <command...>" >&2; exit 2; }
  shift 3
  "$@" 2>&1 | tee "$dir/$stage.run.out"
  rc="${PIPESTATUS[0]}"
  echo "$rc" >"$dir/$stage.exit"
  exit "$rc"
fi

stage="${2:-$action}"
attempt="${3:-0}"
mode="${4:-$action}"
out="$dir/$stage-$attempt.out"

# snapshot: "<hash> <path>" for every modified or untracked file, to tell what a step changed.
snapshot() {
  git ls-files -m -o --exclude-standard -z | while IFS= read -r -d '' f; do
    printf '%s %s\n' "$(git hash-object -- "$f" 2>/dev/null || echo deleted)" "$f"
  done | sort
}

# changed_since FILE: paths whose state differs from the snapshot in FILE.
changed_since() { diff <(cat "$1") <(snapshot) | sed -n 's/^[<>] [^ ]* //p' | sort -u; }

record() { # fixed files cost summary [usage-json]
  local summary="${4//$'\n'/ }"
  summary="${summary:0:160}"
  jq -n --arg stage "$stage" --argjson attempt "$attempt" --arg mode "$mode" --argjson fixed "$1" \
    --argjson files "$2" --arg cost "$3" --arg summary "$summary" --argjson usage "${5:-null}" \
    '{stage: $stage, attempt: $attempt, mode: $mode, fixed: $fixed, files: $files, cost: ($cost | tonumber),
      summary: $summary, usage: $usage}' >"$dir/$stage-$attempt.json"
  printf 'HEAL %s attempt=%s mode=%s fixed=%s files=%s cost=$%s summary=%s\n' \
    "$stage" "$attempt" "$mode" "$1" "$(jq 'length' <<<"$2")" "$3" "${summary:0:120}"
}

files_json() { if [[ -n "$1" ]]; then printf '%s\n' "$1" | jq -R . | jq -sc .; else echo '[]'; fi; }

# problem_lines FILE [MAX]: the lines of a run that say what went wrong, as yukta's --quiet picks them.
problem_lines() {
  awk '
    /What went wrong/ { ctx = 3; print; next }
    ctx > 0 && /^\* / { ctx = 0; next }
    ctx > 0 { ctx--; print; next }
    tolower($0) ~ /(^|[^a-z])(error|failed|failure)([^a-z]|$)|exception|see the report at|violation/ { print }
  ' "$1" | cut -c1-300 | awk '!seen[$0]++' | head -"${2:-60}"
}

# failing_tests: test classes with a failure in a report newer than the tests stage's run output.
failing_tests() {
  find . -path '*/build/test-results/*' -name 'TEST-*.xml' -newer "$dir/tests.run.out" -print0 2>/dev/null \
    | xargs -0 -r grep -lE '<(failure|error) ' | sed -E 's#.*/TEST-(.*)\.xml#\1#' | sort -u | head -15
}

# analyzer_command NAME: the command of a healable analyzer (YUKTA_HEAL_ANALYZERS holds name=command lines).
analyzer_command() { sed -n "s/^$1=//p" <<<"${YUKTA_HEAL_ANALYZERS:-}"; }

# failed_analyzers: the healable analyzers whose last run failed, one per line.
failed_analyzers() {
  local name
  while IFS='=' read -r name _; do
    [[ -n "$name" && -e "$dir/$name.exit" && "$(cat "$dir/$name.exit")" != 0 ]] && echo "$name"
  done <<<"${YUKTA_HEAL_ANALYZERS:-}"
  return 0
}

# analyzer_tasks NAME...: the gradle tasks of the named gradle analyzers, once each.
analyzer_tasks() {
  local n
  for n in "$@"; do analyzer_command "$n"; done | grep '^\./gradlew ' \
    | tr ' ' '\n' | grep -v '^-' | grep -v '^\./gradlew$' | grep -v '^$' | awk '!seen[$0]++' | paste -sd' '
}

# fix is routed by its mode; the other actions are their own
key="$action"
[[ "$action" == fix ]] && key="$mode"
case "$key" in
  verify)
    if ! ls "$dir"/*-[0-9]*.json >/dev/null 2>&1 || ! jq -e -s 'any(.[]; .fixed)' "$dir"/*-[0-9]*.json >/dev/null; then
      exit 0
    fi
    echo "HEAL verify running every stage again after a heal"
    # shellcheck disable=SC2086
    gradle --continue $tasks
    exit $?
    ;;
  rerun)
    # One gradle run for every failed gradle analyzer (never parallel gradle: each build would start a
    # daemon), then the other tools one by one. Keeps the exit file of each analyzer up to date.
    mapfile -t failed < <(failed_analyzers)
    ((${#failed[@]})) || { echo "HEAL rerun: nothing failed"; exit 0; }
    rc=0
    gradle_names=() other_names=()
    for n in "${failed[@]}"; do
      if [[ "$(analyzer_command "$n")" == ./gradlew* ]]; then gradle_names+=("$n"); else other_names+=("$n"); fi
    done
    if ((${#gradle_names[@]})); then
      # shellcheck disable=SC2046
      gradle --continue $(analyzer_tasks "${gradle_names[@]}") 2>&1 | tee "$dir/analyzers-gradle.run.out"
      grc="${PIPESTATUS[0]}"
      # ponytail: one gradle run cannot say which analyzer failed, so they all share its exit code
      for n in "${gradle_names[@]}"; do echo "$grc" >"$dir/$n.exit"; done
      ((grc == 0)) || rc=1
    fi
    for n in "${other_names[@]}"; do
      eval "$(analyzer_command "$n")" 2>&1 | tee "$dir/$n.run.out"
      orc="${PIPESTATUS[0]}"
      echo "$orc" >"$dir/$n.exit"
      ((orc == 0)) || rc=1
    done
    failed_analyzers | paste -sd, >"$dir/analyzers.failed"
    exit "$rc"
    ;;
  auto)
    before="$(mktemp)"; snapshot >"$before"
    # shellcheck disable=SC2086
    gradle $tasks >"$out" 2>&1; rc=$?
    changed="$(changed_since "$before")"; rm -f "$before"
    if ((rc == 0)) && [[ -n "$changed" ]]; then fixed=true; else fixed=false; fi
    record "$fixed" "$(files_json "$changed")" 0 "ran ${tasks:-nothing} (exit $rc)"
    # the workflow routes on this: 0 = repaired (the stage runs again), non-zero = the next rung
    [[ "$fixed" == true ]]
    exit $?
    ;;
  haiku | sonnet | opus) ;;
  *) echo "HEAL $stage attempt=$attempt unknown mode '$mode'"; exit 1 ;;
esac

# ---- a Claude attempt ---------------------------------------------------------------------------------
# The stage already ran in the node before; its output is kept in $dir/<stage>.run.out.
model="$mode"
allowed='^$'
: >"$out"
if [[ "$stage" == analyzers ]]; then
  mapfile -t failed < <(failed_analyzers)
  ((${#failed[@]})) || { record true '[]' 0 "no analyzer is failing"; exit 0; }
  tasks="$(analyzer_tasks "${failed[@]}")"
  problems="" verify=""
  for n in "${failed[@]}"; do
    problems+="[$n]"$'\n'"$(problem_lines "$dir/$n.run.out" 25)"$'\n'
    verify+="$(analyzer_command "$n")"$'\n'
  done
  printf '%s\n' "${failed[@]}" | paste -sd, >"$dir/analyzers.failed"
  # only the actionlint fixer may edit the workflows; with another analyzer failing they stay protected
  [[ "${failed[*]}" == actionlint ]] && allowed='^\.github/workflows/[^/]+\.ya?ml$'
else
  verify="./gradlew $tasks -q --console=plain"
  problems="$(problem_lines "$dir/$stage.run.out")"
  [[ "$stage" == tests ]] && problems+=$'\n'"failing test classes: $(failing_tests | paste -sd' ')"
fi

before="$(mktemp)"; snapshot >"$before"
# the working tree as it is now (the developer's own edits included), to diff this attempt against
base="$(git stash create 2>/dev/null)"; base="${base:-HEAD}"
previous=""
for ((i = attempt - 1; i >= 1; i--)); do
  if [[ -f "$dir/$stage-$i.json" ]]; then
    previous="$(jq -r '"Attempt \(.attempt) (\(.mode)) said: \(.summary) [fixed=\(.fixed)]"' "$dir/$stage-$i.json")"
    break
  fi
done
stat="$(git diff --stat | tail -8)"

# Everything that varies per run is in the user prompt; the system prompt is one constant file, so every
# attempt of every run shares the same cached prefix.
prompt="Stage \"$stage\" of the Yukta quality gate failed. Fix the root cause in the source so it passes.
Gradle tasks: $tasks
Problems (from the run):
$problems
${previous:+$previous
}Files already changed in the working tree (by the developer, keep them):
${stat:-none}
Verify with only:
$verify
Then answer with the JSON object only."

schema='{"type":"object","properties":{"fixed":{"type":"boolean"},"summary":{"type":"string"}},"required":["fixed","summary"]}'

# A headless claude must not think it is the session that started the server.
unset_vars=(CLAUDECODE CLAUDE_CODE_SESSION_ID CLAUDE_CODE_ENTRYPOINT CLAUDE_CODE_SSE_PORT
  CLAUDE_CODE_MESSAGING_SOCKET CLAUDE_CODE_MESSAGING_TOKEN CLAUDE_CODE_CHILD_SESSION
  CLAUDE_CODE_SESSION_ATTENDED CLAUDE_PID)
env_u=(); for v in "${unset_vars[@]}"; do env_u+=(-u "$v"); done

# yk_claude MODEL PROMPT: the one launcher of every headless Claude. Slim on purpose: none of the
# user's plugins, skills, settings or MCP servers (their tool schemas cost more than a fix), a fixed tool
# list and a constant system prompt, so the cached prefix is byte-identical across attempts and runs.
# Not --bare: it would also drop the login. The project hooks still load (they keep the gradle guard).
yk_claude() {
  # YUKTA_HEAL_FIXER=1: the repo's PreToolUse hook lets this session run ./gradlew directly.
  env "${env_u[@]}" YUKTA_HEAL_FIXER=1 "${YUKTA_CLAUDE_BIN:-claude}" -p "$2" --model "$1" \
    --setting-sources project --strict-mcp-config --disable-slash-commands \
    --exclude-dynamic-system-prompt-sections \
    --tools Read,Edit,Write,Grep,Glob,Bash --permission-mode acceptEdits \
    --allowedTools "Read" "Edit" "Write" "Grep" "Glob" "Bash(./gradlew:*)" "Bash(codegraph:*)" \
    --disallowedTools "Bash(git commit:*)" "Bash(git push:*)" \
    --append-system-prompt "$(cat "$here/../fixer-rules.md")" --max-budget-usd "$budget" \
    --output-format json --json-schema "$schema" --no-session-persistence 2>>"$out"
}

result="$(yk_claude "$model" "$prompt")"

cost="$(jq -r '.total_cost_usd // 0' <<<"$result" 2>/dev/null || echo 0)"
usage="$(jq -c '.usage // null | if . == null then null else {input: .input_tokens, cache_creation: .cache_creation_input_tokens, cache_read: .cache_read_input_tokens, output: .output_tokens} end' <<<"$result" 2>/dev/null || echo null)"
answer="$(jq -c '.structured_output // (.result | fromjson? ) // {}' <<<"$result" 2>/dev/null || echo '{}')"
claimed="$(jq -r '.fixed // false' <<<"$answer" 2>/dev/null || echo false)"
summary="$(jq -r '.summary // "no answer from claude"' <<<"$answer" 2>/dev/null || echo "no answer from claude")"

# A fix must not break formatting, and is judged on what it changed.
fmt=(); for m in $module; do [[ "$m" == cli ]] || fmt+=(":$m:spotlessApply"); done
gradle "${fmt[@]:-spotlessApply}" >>"$out" 2>&1 || true
changed="$(changed_since "$before")"

violations=""
touched="$(grep -E "$protected" <<<"$changed" | grep -v -E "$allowed")"
if [[ -n "$touched" ]]; then
  violations+=" touched protected path: $(head -3 <<<"$touched" | paste -sd,)."
fi
diffs="$(while IFS= read -r f; do [[ -n "$f" && -f "$f" ]] && git diff -U0 "$base" -- "$f" 2>/dev/null; done <<<"$changed")"
untracked="$(git ls-files -o --exclude-standard | grep -F -x -f <(printf '%s\n' "$changed") | head -50)"
if { grep -E '^\+[^+]' <<<"$diffs"; [[ -n "$untracked" ]] && xargs -d '\n' cat <<<"$untracked"; } 2>/dev/null | grep -Eq "$suppression"; then
  violations+=" added a suppression."
fi
removed="$(grep -cE '^-[^-].*(assert|@Test|verify\()' <<<"$diffs")"
added="$(grep -cE '^\+[^+].*(assert|@Test|verify\()' <<<"$diffs")"
((removed > added)) && violations+=" removed assertions ($removed removed, $added added)."

fixed=false
[[ "$claimed" == true && -z "$violations" && -n "$changed" ]] && fixed=true
[[ "$claimed" == true && -z "$changed" ]] && violations+=" claimed a fix but changed nothing."
[[ -n "$violations" ]] && summary="GUARD:$violations $summary"
record "$fixed" "$(files_json "$changed")" "$cost" "$summary" "$usage"
rm -f "$before"
# the workflow routes on this: 0 = repaired (the stage runs again), non-zero = the next rung
[[ "$fixed" == true ]]
