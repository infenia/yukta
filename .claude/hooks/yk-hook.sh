#!/usr/bin/env bash
# Claude Code hooks that route builds, tests and checks of this repo through Yukta (`yk`, the yukta skill).
# Registered in .claude/settings.json as  "$(git rev-parse --show-toplevel)"/.claude/hooks/yk-hook.sh <event>
# so they work when Claude starts in a module folder ($CLAUDE_PROJECT_DIR is then that folder).
#   session-start  remember the uncommitted changes the session began with, start or refresh the dev
#                  server in the background, put yk on PATH, and tell Claude how to build and test
#   pre-bash       deny ./gradlew, gradle, go test/vet/build/fmt and pnpm/npm/npx, naming the yk command to use instead
#   prompt         a prompt about building, testing or checking gets one line naming the yukta skill
#   post-edit      remember the Gradle module of every file the session edits (.yukta-dev/touched-<session>)
#   stop           when the session changed code and no check passed on it, ask once for `yk check`
#                  scoped to the modules the session touched
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

# module_of FILE: the Gradle module a file (relative to the checkout) belongs to, with / as :; "*" for a
# change that touches everything (build-logic, the root build, shared config); nothing for a file that is
# no code (docs, .claude/)
module_of() {
  local f="$1" d
  [[ "$f" =~ \.(java|kts|gradle|go|ts|tsx|json)$ ]] || return 0
  d="$(dirname "$f")"
  while [[ "$d" != . && ! -f "$root/$d/build.gradle.kts" ]]; do d="$(dirname "$d")"; done
  [[ "$d" == build-logic* ]] && { echo '*'; return; }
  if [[ "$d" == . ]]; then
    [[ "$f" =~ \.(kts|gradle)$|^(config|gradle)/ ]] && echo '*'
    return 0
  fi
  echo "${d//\//:}"
}

# modules_to_check: "-m <module>" for each module the session touched (or, without a record, each module
# with uncommitted code changes); empty when everything is affected, so the whole check applies
modules_to_check() {
  local sid mods=() m f
  sid="$(field .session_id)"
  if [[ -n "$sid" && -s "$state/touched-$sid" ]]; then
    mapfile -t mods <"$state/touched-$sid"
  else
    while IFS= read -r f; do
      m="$(module_of "$f")"
      [[ -n "$m" ]] && mods+=("$m")
    done < <(git -C "$root" status --porcelain "${code[@]}" | sed -E 's/^.. //; s/.* -> //')
  fi
  ((${#mods[@]})) || return 0
  printf '%s\n' "${mods[@]}" | grep -qx '\*' && return 0
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
    ctx="Yukta dev server: ${server:-starting}. Build, test and check this repo only through Yukta (yukta skill): \`yk gradle <gradle args>\` (e.g. \`yk gradle :core:test --tests '<FQCN>'\`), \`yk tool -C <ui|cli> <command>\` for go and pnpm/npx; \`yk\` is examples/11-dogfood/yk. Direct ./gradlew, gradle, go and pnpm/npm/npx are blocked."
    jq -n --arg c "$ctx" '{hookSpecificOutput: {hookEventName: "SessionStart", additionalContext: $c}}'
    ;;
  prompt)
    shopt -s nocasematch
    [[ "$(field .prompt)" =~ (^|[^a-z])(test|tests|build|compile|check|gradle|lint|spotless|checkstyle|pmd|spotbugs|coverage|ci|failing|issue|epic)([^a-z]|$) ]] || exit 0
    jq -n '{hookSpecificOutput: {hookEventName: "UserPromptSubmit", additionalContext: "Builds, tests and checks of this repo run through Yukta (yukta skill): yk gradle <args>, yk tool -C <ui|cli> <command>. Direct gradle, go and pnpm are blocked."}}'
    ;;
  post-edit)
    sid="$(field .session_id)"
    f="$(field .tool_input.file_path)"
    [[ -n "$sid" && -n "$f" && "$f" == "$root"/* ]] || exit 0
    m="$(module_of "${f#"$root"/}")"
    [[ -n "$m" ]] || exit 0
    mkdir -p "$state"
    grep -qxF -- "$m" "$state/touched-$sid" 2>/dev/null || echo "$m" >>"$state/touched-$sid"
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
    # rebuild step 1: only yk gradle is exposed, so no nudge towards yk check. Remove this line to re-enable it.
    exit 0
    [[ "$(field .stop_hook_active)" == true ]] && exit 0
    [[ -n "$(git -C "$root" status --porcelain "${code[@]}" 2>/dev/null)" ]] || exit 0
    sid="$(field .session_id)"
    sum="$(changes_sum)"
    # nothing changed since the session began: the changes are someone else's
    [[ -n "$sid" && "$(cat "$state/baseline-$sid" 2>/dev/null)" == "$sum" ]] && exit 0
    [[ "$(cat "$state/nudged" 2>/dev/null)" != "$sum" ]] || exit 0
    # a check already passed on exactly these changes: nothing left to ask for
    read -r passed_sum passed_status _ 2>/dev/null <"$state/last-result" || true
    if [[ "${passed_sum:-}" == "$sum" && "${passed_status:-}" == SUCCESS ]]; then
      [[ -z "$sid" ]] || : >"$state/touched-$sid"
      exit 0
    fi
    mkdir -p "$state" && echo "$sum" >"$state/nudged"
    reason="Uncommitted code changes: run the quality gate before finishing: yk check $(modules_to_check)(yukta skill; it heals what it can, review its healed= changes), then report any Yukta defect with yk finding. YUKTA_DOGFOOD=off skips this."
    jq -n --arg r "$reason" '{decision: "block", reason: $r}'
    ;;
esac
exit 0
