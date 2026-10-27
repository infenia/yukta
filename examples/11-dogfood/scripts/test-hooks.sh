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
touch core/build.gradle.kts plugins/p/build.gradle.kts core/src/A.java plugins/p/src/B.java docs/docs.json
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

# post-edit: the module of every edited file is remembered, files outside any module and docs are not
for f in core/src/A.java plugins/p/src/B.java docs/docs.json core/src/A.java "$repo/../outside.java"; do
  run post-edit "$(input s1 "$repo/${f#"$repo"/}")" || true
done
[[ "$(sort .yukta-dev/touched-s1 | paste -sd,)" == "core,plugins:p" ]] || fail "touched: $(paste -sd, .yukta-dev/touched-s1)"

# stop: silent while only yk gradle/tool/plan/check are exposed (the nudge for yk check is in backup/scripts/test-hooks.stop.sh)
echo "// edit" >>core/src/A.java
[[ -z "$(run stop "$(input s1)")" ]] || fail "stop nudged"

echo "ok   hooks"
