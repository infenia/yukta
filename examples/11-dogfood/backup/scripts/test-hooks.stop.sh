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

# stop: scoped to the touched modules, once, and not when a check already passed on these changes
echo "// edit" >>core/src/A.java
echo "// other edit" >>plugins/p/src/B.java
run stop "$(input s1)" | jq -r .reason | grep -q "yk check -m core -m plugins:p " || fail "stop is not scoped to the touched modules"
[[ -z "$(run stop "$(input s1)")" ]] || fail "the nudge repeated for the same changes"
echo "// again" >>core/src/A.java
sum="$(examples/11-dogfood/yk sum)"
printf '%s\tSUCCESS\tcore\t-\t1\n' "$sum" >.yukta-dev/last-result
[[ -z "$(run stop "$(input s1)")" ]] || fail "asked for a check that already passed"
[[ ! -s .yukta-dev/touched-s1 ]] || fail "touched modules were not cleared after the passing check"

# a build-logic edit means the whole check
git checkout -q . && mkdir -p build-logic && touch build-logic/x.kts && git add -A && git -c user.email=t@t -c user.name=t commit -qm bl
run post-edit "$(input s2 "$repo/build-logic/x.kts")"
echo "// c" >>build-logic/x.kts
run stop "$(input s2)" | jq -r .reason | grep -q "yk check (yukta" || fail "a build-logic change does not ask for the whole check"
echo "ok   hooks"
