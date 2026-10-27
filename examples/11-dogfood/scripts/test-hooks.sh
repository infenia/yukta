#!/usr/bin/env bash
# Offline test of .claude/hooks/yk-hook.sh in a throwaway git checkout with a stub `yk` (no server needed).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
hook="$here/../../../.claude/hooks/yk-hook.sh"
fail() { echo "FAIL $*" >&2; exit 1; }

repo="$(mktemp -d)"
trap 'rm -rf "$repo"' EXIT
cd "$repo"
git init -q
mkdir -p examples/11-dogfood core/src plugins/p/src docs
cat >examples/11-dogfood/yk <<'SH'
#!/usr/bin/env bash
# stub: `yk sum` is a hash of the working tree, the rest does nothing
if [[ "${1:-}" == sum ]]; then (git diff HEAD; git status --porcelain) | sha1sum | cut -d' ' -f1; fi
SH
chmod +x examples/11-dogfood/yk
echo ".yukta-dev/" >.gitignore
touch .gitignore core/build.gradle.kts plugins/p/build.gradle.kts core/src/A.java plugins/p/src/B.java docs/docs.json
git add -A && git -c user.email=t@t -c user.name=t commit -qm init

run() { echo "$2" | "$hook" "$1"; }   # event json-input
input() { jq -nc --arg s "${1:-s1}" --arg f "${2:-}" --arg p "${3:-}" '{session_id: $s, stop_hook_active: false, prompt: $p, tool_input: {file_path: $f, command: $p}}'; }

# prompt: only prompts about building and testing get the line
[[ "$(run prompt "$(input s1 '' 'please run the tests for core')" | jq -r .hookSpecificOutput.additionalContext)" == *yukta* ]] || fail "prompt about tests gets no hint"
[[ -z "$(run prompt "$(input s1 '' 'explain the architecture')")" ]] || fail "an unrelated prompt got a hint"

# pre-bash: direct gradle is denied with the yk command
reason="$(run pre-bash "$(input s1 '' './gradlew :core:test --tests X')" | jq -r .hookSpecificOutput.permissionDecisionReason)"
[[ "$reason" == *"yk gradle :core:test --tests X"* ]] || fail "gradle not redirected: $reason"
[[ -z "$(run pre-bash "$(input s1 '' 'ls -la')")" ]] || fail "ls was denied"

# stop: asks once for yk check when the session changed code, and not when a check already passed on these changes
echo "// edit" >>core/src/A.java
reason="$(run stop "$(input s1)" | jq -r .reason)"
[[ "$reason" == *"yk check"* && "$reason" != *" -m "* ]] || fail "stop does not ask for yk check: $reason"
[[ -z "$(run stop "$(input s1)")" ]] || fail "the nudge repeated for the same changes"
[[ -z "$(run stop "$(jq -c '.stop_hook_active = true' <<<"$(input s1)")")" ]] || fail "stop nudged inside a stop hook"
echo "// more" >>core/src/A.java
sum="$(examples/11-dogfood/yk sum)"
mkdir -p .yukta-dev
printf '%s\tSUCCESS\targs:x\t-\t1\n' "$sum" >.yukta-dev/last-result
run stop "$(input s1)" | jq -r .reason | grep -q "yk check" || fail "a passing yk gradle silenced the nudge"
echo "// again" >>core/src/A.java
sum="$(examples/11-dogfood/yk sum)"
printf '%s\tSUCCESS\tcheck\t-\t1\n' "$sum" >.yukta-dev/last-result
[[ -z "$(run stop "$(input s1)")" ]] || fail "asked for a check that already passed"
# the changes a session began with are someone else's
echo "// third" >>core/src/A.java
examples/11-dogfood/yk sum >.yukta-dev/baseline-s2
[[ -z "$(run stop "$(input s2)")" ]] || fail "nudged for changes that were there at the start"

# yk check --stage/--skip: filter_plan keeps or drops stages by name, either spelling of - and _
(
  source "$here/_lib.sh"
  plan="$(printf '# note\nstyle\t.\tc1\t\nui_tsc\tui\tc2\t\nreuse\t.\tc3\t\n')"
  [[ "$(filter_plan "style,ui-tsc" "" <<<"$plan" | cut -f1 | paste -sd,)" == "# note,style,ui_tsc" ]] || fail "--stage"
  [[ "$(filter_plan "" "style" <<<"$plan" | cut -f1 | paste -sd,)" == "# note,ui_tsc,reuse" ]] || fail "--skip"
  [[ "$(filter_plan "" "" <<<"$plan" | wc -l)" == 4 ]] || fail "no filter"
) || exit 1

echo "ok   hooks"
