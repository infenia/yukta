#!/usr/bin/env bash
# Claude Code hooks that route builds, tests and checks of this repo through Yukta (`yk`, the yukta skill).
# Registered in .claude/settings.json as  "$(git rev-parse --show-toplevel)"/.claude/hooks/yk-hook.sh <event>
# so they work when Claude starts in a module folder ($CLAUDE_PROJECT_DIR is then that folder).
#   session-start  remember the uncommitted changes the session began with, start or refresh the dev
#                  server in the background, put yk on PATH, and tell Claude how to build and test
#   pre-bash       deny ./gradlew, gradle and go test/vet, naming the yk command to use instead
#   stop           when the session changed code and no check ran on it, ask once for `yk check`
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
changes_sum() { (git -C "$root" diff HEAD; git -C "$root" status --porcelain "${code[@]}") 2>/dev/null | sha1sum | cut -d' ' -f1; }

# modules_of_changes: "-m <module>" for each Gradle module with uncommitted code changes (empty when a
# change is outside every module, e.g. in build-logic or the root build: then the whole check applies).
modules_of_changes() {
  local f d mods=()
  while IFS= read -r f; do
    d="$(dirname "$f")"
    while [[ "$d" != . && ! -f "$root/$d/build.gradle.kts" ]]; do d="$(dirname "$d")"; done
    [[ "$d" == build-logic* ]] && return 0
    if [[ "$d" == . ]]; then
      # the root build or shared config changes everything; other files outside modules (docs) don't
      [[ "$f" =~ \.(kts|gradle)$|^(config|gradle)/ ]] && return 0
      continue
    fi
    mods+=("${d//\//:}")
  done < <(git -C "$root" status --porcelain "${code[@]}" | sed -E 's/^.. //; s/.* -> //')
  # none at all (every change outside a module, e.g. .claude/): the whole check, so print nothing
  ((${#mods[@]})) || return 0
  printf -- '-m %s ' $(printf '%s\n' "${mods[@]}" | sort -u)
}

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
    ctx="Yukta dev server: ${server:-starting}. Build, test and check this repo only through Yukta (yukta skill): \`yk gradle <gradle args>\` (e.g. \`yk gradle :core:test --tests '<FQCN>'\`), \`yk check -m <module>\`, \`yk check\` before a commit; \`yk\` is examples/11-dogfood/yk. Direct ./gradlew, gradle and go test are blocked."
    jq -n --arg c "$ctx" '{hookSpecificOutput: {hookEventName: "SessionStart", additionalContext: $c}}'
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
        go) [[ "${w[1]:-}" =~ ^(test|vet)$ ]] && { hit=go; break; } ;;
      esac
    done < <(sed -E 's/(&&|\|\||[;|&()])/\n/g' <<<"$cmd")
    [[ -n "$hit" ]] || exit 0
    # Running the app being developed is not a build or a check: bootRun/nativeRun stay direct.
    [[ " ${args[*]:-} " =~ [[:space:]:](bootRun|nativeRun)[[:space:]] ]] && exit 0
    if [[ "$hit" == go ]]; then
      use="yk gradle :cli:check"
    else
      tasks=() kept=()
      for a in "${args[@]}"; do
        case "$a" in
          -q | --quiet | --console=* | --continue | --offline | --daemon | --no-daemon | --parallel) ;;
          [0-9]\>* | \>* | \<*) ;;  # redirections
          -*) kept+=("$a") ;;
          *) kept+=("$a"); tasks+=("$a") ;;
        esac
      done
      t="${tasks[*]:-}"
      if [[ "$t" =~ ^(spotlessApply\ )?(check|build)$ && ${#kept[@]} -eq ${#tasks[@]} ]]; then
        use="yk check"
      elif [[ -n "$t" && ${#kept[@]} -eq ${#tasks[@]} ]] && ! grep -qvE '^:[A-Za-z0-9:_-]+:check$' <<<"${t// /$'\n'}"; then
        use="yk check$(for x in "${tasks[@]}"; do x="${x#:}"; printf ' -m %s' "${x%:check}"; done)"
      else
        use="yk gradle ${kept[*]:-<tasks>}"
      fi
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
    mkdir -p "$state" && echo "$sum" >"$state/nudged"
    reason="Uncommitted code changes: run the quality gate before finishing: yk check $(modules_of_changes)(yukta skill; it heals what it can, review its healed= changes), then report any Yukta defect with yk finding. YUKTA_DOGFOOD=off skips this."
    jq -n --arg r "$reason" '{decision: "block", reason: $r}'
    ;;
esac
exit 0
