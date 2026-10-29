#!/usr/bin/env bash
# Offline test of the fixer roles (claude.sh): the system prompt is the common rules plus fixer-rules/<kind>.md picked by the stage,
# and the common rules alone for a stage without a role.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
cat >"$dir/claude" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$@" >"$(dirname "$0")/argv"
echo '{"is_error":false,"structured_output":{"fixed":false,"summary":"x"}}'
SH
chmod +x "$dir/claude"
echo "COMMON" >"$dir/rules"
export YUKTA_FIXER_MCP=off YK_MODEL=haiku YK_RULES="$dir/rules" YK_SCHEMA='{}' YK_CLAUDE_BIN="$dir/claude"
# stage -> expected role marker (the first words of fixer-rules/<kind>.md)
while read -r stage role; do
  YK_STAGE="$stage" bash "$here/scripts/claude.sh" <<<'{"prompt":"p"}' >/dev/null
  grep -q '^COMMON' "$dir/argv" || fail "$stage: common rules missing"
  if [[ "$role" == - ]]; then
    grep -q '^Role:' "$dir/argv" && fail "$stage: got a role"
  else
    grep -q "^Role: $role" "$dir/argv" || fail "$stage: expected role '$role'"
  fi
done <<'LIST'
style static-analysis
spotbugs SpotBugs
tests Java test
coverage coverage
compile compile
ui_vitest UI test
ui_tsc UI static-check
cli_vet Go
reuse -
spotless -
LIST
echo "PASS fixer roles"
