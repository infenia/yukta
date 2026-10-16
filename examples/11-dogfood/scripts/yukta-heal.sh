#!/usr/bin/env bash
# One self-healing step of the dogfood check workflow (see self_heal in _lib.sh). Run by a Yukta
# PROCESS_EXECUTOR node, but usable by hand.
#
# Usage: yukta-heal.sh <stage> <attempt> <mode>
#   mode auto    run the deterministic fix tasks (YUKTA_HEAL_TASKS, e.g. spotlessApply)
#   mode fresh   a new headless Claude Code (Sonnet) given a short brief of the failure
#   mode fork    a fork of the calling Claude Code session ($YUKTA_FORK_SESSION), which knows what
#                the change was; falls back to fresh when there is no such session
#   mode verify  re-run every stage's tasks, but only when an earlier step healed something
#
# Environment: YUKTA_HEAL_DIR (records, required), YUKTA_HEAL_TASKS (the stage's gradle tasks),
# YUKTA_MODULE (module the run is scoped to), YUKTA_FORK_SESSION, YUKTA_HEAL_BUDGET (USD per
# attempt, default 1), YUKTA_HEAL_MODEL (default sonnet).
#
# It prints one line and always exits 0 (the recheck node decides), except verify:
#   HEAL <stage> attempt=<n> mode=<m> fixed=<true|false> files=<n> cost=$<x> summary=<text>
# and writes <YUKTA_HEAL_DIR>/<stage>-<attempt>.json. It never commits, never reverts, and marks the
# attempt as not fixed when it touched protected paths, added a suppression or removed assertions.
set -uo pipefail

stage="${1:?usage: yukta-heal.sh <stage> <attempt> <mode>}"
attempt="${2:?attempt}"
mode="${3:?mode}"
dir="${YUKTA_HEAL_DIR:?YUKTA_HEAL_DIR is required}"
tasks="${YUKTA_HEAL_TASKS:-}"
module="${YUKTA_MODULE:-}"
budget="${YUKTA_HEAL_BUDGET:-1}"
model="${YUKTA_HEAL_MODEL:-sonnet}"
mkdir -p "$dir"
out="$dir/$stage-$attempt.out"

# Paths a fixer must not touch: the rules it is being measured against, the build and this tooling.
protected='^(config/|build-logic/|gradle/|\.github/|\.claude/|examples/11-dogfood/|REUSE\.toml|.*\.gradle\.kts$)'
# Ways to make a check pass without fixing the code.
suppression='SuppressFBWarnings|NOPMD|CHECKSTYLE:OFF|@SuppressWarnings\("(PMD|checkstyle)|@Disabled|spotless:off'

gradle() { ./gradlew "$@" -q --console=plain; }

# snapshot: "<hash> <path>" for every modified or untracked file, to tell what a step changed.
snapshot() {
  git ls-files -m -o --exclude-standard -z | while IFS= read -r -d '' f; do
    printf '%s %s\n' "$(git hash-object -- "$f" 2>/dev/null || echo deleted)" "$f"
  done | sort
}

# changed_since FILE: paths whose state differs from the snapshot in FILE.
changed_since() { diff <(cat "$1") <(snapshot) | sed -n 's/^[<>] [^ ]* //p' | sort -u; }

record() { # fixed files cost summary
  local summary="${4//$'\n'/ }"
  summary="${summary:0:160}"
  jq -n --arg stage "$stage" --argjson attempt "$attempt" --arg mode "$mode" --argjson fixed "$1" \
    --argjson files "$2" --arg cost "$3" --arg summary "$summary" \
    '{stage: $stage, attempt: $attempt, mode: $mode, fixed: $fixed, files: $files, cost: ($cost | tonumber), summary: $summary}' \
    >"$dir/$stage-$attempt.json"
  printf 'HEAL %s attempt=%s mode=%s fixed=%s files=%s cost=$%s summary=%s\n' \
    "$stage" "$attempt" "$mode" "$1" "$(jq 'length' <<<"$2")" "$3" "${summary:0:120}"
}

files_json() { if [[ -n "$1" ]]; then printf '%s\n' "$1" | jq -R . | jq -sc .; else echo '[]'; fi; }

# problem_lines FILE: the lines of a gradle run that say what went wrong, as yukta's --quiet picks them.
problem_lines() {
  awk '
    /What went wrong/ { ctx = 3; print; next }
    ctx > 0 && /^\* / { ctx = 0; next }
    ctx > 0 { ctx--; print; next }
    tolower($0) ~ /(^|[^a-z])(error|failed|failure)([^a-z]|$)|exception|see the report at|violation/ { print }
  ' "$1" | cut -c1-300 | awk '!seen[$0]++' | head -60
}

failing_tests() {
  find . -path '*/build/test-results/*' -name 'TEST-*.xml' -newer "$out" -print0 2>/dev/null \
    | xargs -0 -r grep -lE '<(failure|error) ' | sed -E 's#.*/TEST-(.*)\.xml#\1#' | sort -u | head -15
}

case "$mode" in
  verify)
    if ! ls "$dir"/*.json >/dev/null 2>&1 || ! jq -e -s 'any(.[]; .fixed)' "$dir"/*.json >/dev/null; then
      echo "HEAL verify skipped: nothing was healed"
      exit 0
    fi
    echo "HEAL verify running every stage again after a heal"
    # shellcheck disable=SC2086
    gradle $tasks
    exit $?
    ;;
  auto)
    before="$(mktemp)"; snapshot >"$before"
    # shellcheck disable=SC2086
    gradle $tasks >"$out" 2>&1; rc=$?
    changed="$(changed_since "$before")"; rm -f "$before"
    if ((rc == 0)) && [[ -n "$changed" ]]; then fixed=true; else fixed=false; fi
    record "$fixed" "$(files_json "$changed")" 0 "ran ${tasks:-nothing} (exit $rc)"
    exit 0
    ;;
  fresh | fork) ;;
  *) echo "HEAL $stage attempt=$attempt unknown mode '$mode'"; exit 0 ;;
esac

# --- claude attempt -------------------------------------------------------------------------------
# The stage ran in the previous node, but its output is not here; run it again (up-to-date tasks are
# skipped, a failing one fails fast) to get the cause fresh and to notice a flaky stage.
# shellcheck disable=SC2086
gradle --continue $tasks >"$out" 2>&1 && {
  record true '[]' 0 "stage passes now without a change"
  exit 0
}

before="$(mktemp)"; snapshot >"$before"
# the working tree as it is now (the developer's own edits included), to diff this attempt against
base="$(git stash create 2>/dev/null)"; base="${base:-HEAD}"
problems="$(problem_lines "$out")"
[[ "$stage" == tests ]] && problems+=$'\n'"failing test classes: $(failing_tests | paste -sd' ')"
previous=""
[[ "$attempt" -gt 1 && -f "$dir/$stage-$((attempt - 1)).json" ]] \
  && previous="$(jq -r '"Attempt \(.attempt) (\(.mode)) said: \(.summary) [fixed=\(.fixed)]"' "$dir/$stage-$((attempt - 1)).json")"
stat="$(git diff --stat | tail -8)"

prompt="Stage \"$stage\" of the Yukta quality gate failed. Fix the root cause in the source so it passes.
Gradle tasks: $tasks
Problems (from the run):
$problems
${previous:+$previous
}Files already changed in the working tree (by the developer, keep them):
${stat:-none}
Verify with only: ./gradlew $tasks -q --console=plain. Then answer with the JSON object only."

rules='You are a repair step of an automated build, not an interactive assistant. Rules: fix the
cause, not the symptom; touch the fewest files; never add suppressions (SuppressFBWarnings, NOPMD,
CHECKSTYLE:OFF, SuppressWarnings for PMD or checkstyle), never disable, delete or weaken tests or
assertions; never edit config/, build-logic/, gradle/, .github/ or .claude/; never run git commit or
git push; do not start servers. If you cannot fix it within these rules, say so with fixed=false.'

schema='{"type":"object","properties":{"fixed":{"type":"boolean"},"summary":{"type":"string"}},"required":["fixed","summary"]}'

# A headless claude must not think it is the session that started the server.
unset_vars=(CLAUDECODE CLAUDE_CODE_SESSION_ID CLAUDE_CODE_ENTRYPOINT CLAUDE_CODE_SSE_PORT
  CLAUDE_CODE_MESSAGING_SOCKET CLAUDE_CODE_MESSAGING_TOKEN CLAUDE_CODE_CHILD_SESSION
  CLAUDE_CODE_SESSION_ATTENDED CLAUDE_PID)
env_u=(); for v in "${unset_vars[@]}"; do env_u+=(-u "$v"); done

run_claude() { # extra session args...
  env "${env_u[@]}" claude -p "$prompt" --model "$model" --permission-mode acceptEdits \
    --allowedTools "Read" "Edit" "Write" "Grep" "Glob" "Bash(./gradlew:*)" \
    --disallowedTools "Bash(git commit:*)" "Bash(git push:*)" "Skill" "Agent" \
    --append-system-prompt "$rules" --max-budget-usd "$budget" \
    --output-format json --json-schema "$schema" "$@" 2>>"$out"
}

result=""
if [[ "$mode" == fork && -n "${YUKTA_FORK_SESSION:-}" ]]; then
  result="$(run_claude --resume "$YUKTA_FORK_SESSION" --fork-session --no-session-persistence)"
  jq -e '.is_error == false' <<<"$result" >/dev/null 2>&1 || result=""   # no such session: go fresh
fi
[[ -n "$result" ]] || result="$(run_claude --no-session-persistence)"

cost="$(jq -r '.total_cost_usd // 0' <<<"$result" 2>/dev/null || echo 0)"
answer="$(jq -c '.structured_output // (.result | fromjson? ) // {}' <<<"$result" 2>/dev/null || echo '{}')"
claimed="$(jq -r '.fixed // false' <<<"$answer" 2>/dev/null || echo false)"
summary="$(jq -r '.summary // "no answer from claude"' <<<"$answer" 2>/dev/null || echo "no answer from claude")"

# A fix must not break formatting, and is judged on what it changed.
gradle "${module:+:$module:}spotlessApply" >>"$out" 2>&1 || true
changed="$(changed_since "$before")"

violations=""
if grep -Eq "$protected" <<<"$changed"; then
  violations+=" touched protected path: $(grep -E "$protected" <<<"$changed" | head -3 | paste -sd,)."
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
record "$fixed" "$(files_json "$changed")" "$cost" "$summary"
rm -f "$before"
exit 0
