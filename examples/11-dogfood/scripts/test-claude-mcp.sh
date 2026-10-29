#!/usr/bin/env bash
# Offline test of the MCP access of the headless fixers (claude.sh / _claude.sh) against a fake claude that records its arguments:
# none without a key, read tools only with one, the key never in an argument, and YUKTA_FIXER_MCP=off.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
cat >"$dir/claude" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$@" >"$(dirname "$0")/argv"
echo "${YK_MCP_KEY:-}" >"$(dirname "$0")/key"
echo '{"is_error":false,"structured_output":{"fixed":false,"summary":"x"}}'
SH
chmod +x "$dir/claude"
echo "rules" >"$dir/rules"
export YUKTA_DEV_HOME="$dir/home" DEV_HOME="$dir/home" YK_MODEL=haiku YK_RULES="$dir/rules" YK_SCHEMA='{}' YK_CLAUDE_BIN="$dir/claude"
unset YUKTA_FIXER_MCP YUKTA_FIXER_MCP_KEY YUKTA_SERVER_URL YK_MCP_URL YK_MCP_KEY
run() { rm -f "$dir/argv" "$dir/key"; echo '{"prompt":"fix it"}' | bash "$here/scripts/claude.sh" >/dev/null; }

run
grep -q -- '--strict-mcp-config' "$dir/argv" || fail "strict-mcp-config is gone"
grep -q -- '--mcp-config' "$dir/argv" && fail "MCP configured without a key"

mkdir -p "$DEV_HOME" && echo secretkey >"$DEV_HOME/fixer-key"
run
grep -q -- '--mcp-config' "$dir/argv" || fail "no --mcp-config with the dev fixer key"
grep -q 'mcp__yukta__get_node_output' "$dir/argv" || fail "get_node_output not allowed"
grep -q 'mcp__yukta__get_workflow_history' "$dir/argv" || fail "get_workflow_history not allowed"
for t in start_workflow control_workflow control_node signal_node create_session; do
  grep -qx "mcp__yukta__$t" "$dir/argv" || fail "$t is not denied"
done
# allowed list holds reads only: every mcp__yukta__ name before --disallowedTools is a getter
awk '/^--disallowedTools$/ { exit } /^mcp__yukta__/ { print }' "$dir/argv" | grep -qv '^mcp__yukta__get_' && fail "a non-read MCP tool is allowed"
grep -q 'secretkey' "$dir/argv" && fail "the key is in an argument"
[[ "$(cat "$dir/key")" == secretkey ]] || fail "the key does not reach claude by environment"
grep -q '"X-API-Key":"${YK_MCP_KEY}"' "$dir/argv" || fail "header is not an environment reference: $(grep mcpServers "$dir/argv")"

YUKTA_FIXER_MCP=off run
grep -q -- '--mcp-config' "$dir/argv" && fail "YUKTA_FIXER_MCP=off still configures MCP"

# another server needs an explicit key; the dev key file is not used for it
YUKTA_SERVER_URL=http://other:1 run
grep -q -- '--mcp-config' "$dir/argv" && fail "the dev fixer key was sent to another server"
YUKTA_SERVER_URL=http://other:1 YUKTA_FIXER_MCP_KEY=k2 run
grep -q 'http://other:1/mcp' "$dir/argv" || fail "explicit server and key not used"
echo "ok   claude-mcp"
