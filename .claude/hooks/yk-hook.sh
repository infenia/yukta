#!/usr/bin/env bash
# Claude Code hooks that route builds, tests and checks of this repo through Yukta (`yk`, the yukta skill).
# Registered in .claude/settings.json as  "$(git rev-parse --show-toplevel)"/.claude/hooks/yk-hook.sh <event>
# so they work when Claude starts in a module folder ($CLAUDE_PROJECT_DIR is then that folder).
#   session-start  remember the uncommitted changes the session began with, start or refresh the dev
#                  server in the background, put yk on PATH, and tell Claude how to build and test
#   pre-bash       deny ./gradlew, gradle, go test/vet/build/fmt and pnpm/npm/npx, naming the yk command to use instead
#   prompt         a prompt about building, testing or checking gets one line naming the yukta skill
#   stop           when the session changed code and no `yk check` passed on it, ask once to run it
# Off: YUKTA_DOGFOOD=off. Headless fixers (YUKTA_HEAL_FIXER=1) run gradle themselves and skip all of it.
set -uo pipefail
[[ "${YUKTA_DOGFOOD:-on}" == off || -n "${YUKTA_HEAL_FIXER:-}" ]] && exit 0
input="$(cat)"
root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
yk="$root/examples/11-dogfood/yk"
[[ -x "$yk" ]] || exit 0
state="$root/.yukta-dev"
code=(-- '*.java' '*.kts' '*.gradle' '*.go' '*.ts' '*.tsx' '*.json')

field() { jq -r "$1 // empty" <<<"$input" 2>/dev/null; }
changes_sum() { "$yk" sum 2>/dev/null; }

case "${1:-}" in
  session-start)
    sid="$(field .session_id)"
    mkdir -p "$state"
    find "$state" -maxdepth 1 -name 'baseline-*' -mtime +7 -delete 2>/dev/null
    # a resumed session keeps its first baseline
    [[ -z "$sid" || -e "$state/baseline-$sid" ]] || changes_sum >"$state/baseline-$sid"
    [[ "$(field .source)" == compact ]] || "$yk" up --background >/dev/null 2>&1
    if [[ -n "${CLAUDE_ENV_FILE:-}" ]]; then
      echo "export PATH=\"$root/examples/11-dogfood:\$PATH\"" >>"$CLAUDE_ENV_FILE"
    fi
    server="$(timeout 5 "$yk" status 2>/dev/null | head -1)"
    ctx="Yukta dev server: ${server:-starting}. Build, test and check this repo only through Yukta (yukta skill): \`yk gradle <gradle args>\` (e.g. \`yk gradle :core:test --tests '<FQCN>'\`), \`yk tool -C <ui|cli> <command>\` for go and pnpm/npx, `yk check` for the gate on your changes (run it in the background); \`yk\` is examples/11-dogfood/yk. Direct ./gradlew, gradle, go and pnpm/npm/npx are blocked."
    jq -n --arg c "$ctx" '{hookSpecificOutput: {hookEventName: "SessionStart", additionalContext: $c}}'
    ;;
  prompt)
    shopt -s nocasematch
    [[ "$(field .prompt)" =~ (^|[^a-z])(test|tests|build|compile|check|gradle|lint|spotless|checkstyle|pmd|spotbugs|coverage|ci|failing|issue|epic)([^a-z]|$) ]] || exit 0
    jq -n '{hookSpecificOutput: {hookEventName: "UserPromptSubmit", additionalContext: "Builds, tests and checks of this repo run through Yukta (yukta skill): yk gradle <args>, yk tool -C <ui|cli> <command>, yk check (gate; background it). Direct gradle, go and pnpm are blocked."}}'
    ;;
  pre-bash)
    cmd="$(field .tool_input.command)"
    [[ -n "$cmd" ]] || exit 0
    hit="" args=()
    # Each simple command (split on ; && || |), without leading VAR=value, env, timeout N, nice, exec.
    while IFS= read -r seg; do
      read -r -a w <<<"$seg"
      while ((${#w[@]})) && [[ "${w[0]}" =~ ^[A-Za-z_][A-Za-z0-9_]*=|^(env|exec|nice|time|command)$ || "${w[0]}" =~ ^[0-9]+[smh]?$ || "${w[0]}" == timeout ]]; do
        w=("${w[@]:1}")
      done
      ((${#w[@]})) || continue
      case "$(basename -- "${w[0]}")" in
        gradlew | gradle) hit=gradle args=("${w[@]:1}"); break ;;
        go) [[ "${w[1]:-}" =~ ^(test|vet|build|fmt)$ ]] && { hit=go; args=("${w[@]}"); break; } ;;
        pnpm | npm | npx | pnpx) hit=js args=("${w[@]}"); break ;;
      esac
    done < <(sed -E 's/(&&|\|\||[;|&()])/\n/g' <<<"$cmd")
    [[ -n "$hit" ]] || exit 0
    # Running the app being developed is not a build or a check: bootRun/nativeRun stay direct.
    [[ " ${args[*]:-} " =~ [[:space:]:](bootRun|nativeRun)[[:space:]] ]] && exit 0
    if [[ "$hit" == go ]]; then
      use="yk tool -C cli ${args[*]}"
    elif [[ "$hit" == js ]]; then
      use="yk tool -C ui ${args[*]}"
    else
      kept=()
      for a in "${args[@]}"; do
        case "$a" in
          -q | --quiet | --console=* | --continue | --offline | --daemon | --no-daemon | --parallel) ;;
          [0-9]\>* | \>* | \<*) ;;  # redirections
          -*) kept+=("$a") ;;
          *) kept+=("$a") ;;
        esac
      done
      use="yk gradle ${kept[*]:-<tasks>}"
    fi
    reason="Builds and tests of this repo run through Yukta (yukta skill). Run instead: $use   (yk is examples/11-dogfood/yk; the run prints a RESULT line). To bypass for one session the user can set YUKTA_DOGFOOD=off."
    jq -n --arg r "$reason" '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: $r}}'
    ;;
  stop)
    [[ "$(field .stop_hook_active)" == true ]] && exit 0
    [[ -n "$(git -C "$root" status --porcelain "${code[@]}" 2>/dev/null)" ]] || exit 0
    sid="$(field .session_id)"
    sum="$(changes_sum)"
    # nothing changed since the session began: the changes are someone else's
    [[ -n "$sid" && "$(cat "$state/baseline-$sid" 2>/dev/null)" == "$sum" ]] && exit 0
    [[ "$(cat "$state/nudged" 2>/dev/null)" != "$sum" ]] || exit 0
    # a check already passed on exactly these changes: nothing left to ask for
    read -r passed_sum passed_status passed_scope _ 2>/dev/null <"$state/last-result" || true
    [[ "${passed_sum:-}" == "$sum" && "${passed_status:-}" == SUCCESS && "${passed_scope:-}" == check ]] && exit 0
    mkdir -p "$state" && echo "$sum" >"$state/nudged"
    reason="Uncommitted code changes: run the quality gate before finishing: yk check (yukta skill: start it in the background; it heals what it can, so review its healed= changes with git diff). YUKTA_DOGFOOD=off skips this."
    jq -n --arg r "$reason" '{decision: "block", reason: $r}'
    ;;
esac
exit 0
