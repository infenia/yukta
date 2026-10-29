#!/usr/bin/env bash
# The step of the `claude` workflow: one headless Claude Code session. The message on stdin is {"prompt": "..."}; the
# answer is claude's JSON result on stdout; a non-zero exit is the workflow's failure port.
#
# Environment (a value of "-" is unset; the workflow cannot pass an empty one):
#   YK_MODEL haiku|sonnet|opus   YK_EFFORT low|medium|high   YK_BUDGET USD   YK_RULES system prompt file   YK_SCHEMA JSON schema of the answer
#   YK_STAGE the failed stage: picks the role file fixer-rules/<kind>.md appended to YK_RULES (none for a stage without one)
#   YK_SESSION session id   YK_ADVISOR advisor model (experimental, off when unset)   YK_CLAUDE_BIN a stand-in for `claude` (tests)
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for v in YK_MODEL YK_EFFORT YK_BUDGET YK_RULES YK_SCHEMA YK_SESSION YK_CLAUDE_BIN YK_ADVISOR YK_STAGE; do [[ "${!v:-}" == - ]] && unset "$v"; done
prompt="$(jq -r '.prompt // empty')"
[[ -n "$prompt" ]] || { echo "claude.sh: no prompt on stdin" >&2; exit 2; }
# shellcheck source=_claude.sh
source "$here/_claude.sh"
# A fixer does not read the project's CLAUDE.md (about 5k tokens on every attempt, measured): fixer-rules.md has the few rules it needs.
export CLAUDE_CODE_DISABLE_CLAUDE_MDS=1
# The aliases move when a new model ships; a fixer's cost and behaviour must not.
export ANTHROPIC_DEFAULT_HAIKU_MODEL=claude-haiku-5-5 ANTHROPIC_DEFAULT_SONNET_MODEL=claude-sonnet-5-5
# Read-only MCP access to the server that runs this workflow (YUKTA_FIXER_MCP=off to disable): the fixer reads more of the failed run on
# demand instead of being handed all of it. The dev server has a VIEWER key for it (_lib.sh fixer_key); another server needs
# YUKTA_FIXER_MCP_KEY (a VIEWER key) and YUKTA_SERVER_URL, else the fixer has no MCP, as before.
if [[ "${YUKTA_FIXER_MCP:-on}" != off ]]; then
  source "$here/_lib.sh"
  url="${YUKTA_SERVER_URL:-$DEV_URL}" key="${YUKTA_FIXER_MCP_KEY:-}"
  [[ -n "$key" || "$url" != "$DEV_URL" || ! -s "$DEV_HOME/fixer-key" ]] || key="$(cat "$DEV_HOME/fixer-key")"
  [[ -z "$key" ]] || export YK_MCP_URL="${url%/}/mcp" YK_MCP_KEY="$key"
fi
[[ -z "${YK_CLAUDE_BIN:-}" ]] || export YUKTA_CLAUDE_BIN="$YK_CLAUDE_BIN"
err="$(mktemp)"
tmp=""
trap 'rm -f "$err" "$tmp"' EXIT
# The role of the fixer by stage: one constant file per kind, so the cached prefix stays byte-identical for that kind.
case "${YK_STAGE:-}" in
  style) kind=pmd ;;
  spotbugs | compile | coverage) kind="$YK_STAGE" ;;
  tests) kind=junit ;;
  ui_vitest) kind=vitest ;;
  ui_*) kind=ui ;;
  cli_*) kind=go ;;
  *) kind="" ;;
esac
rules="${YK_RULES:?YK_RULES}"
if [[ -f "$here/../fixer-rules/$kind.md" ]]; then
  tmp="$(mktemp)"; rules="$tmp"
  cat "$YK_RULES" "$here/../fixer-rules/$kind.md" >"$tmp"
fi
yk_claude "${YK_MODEL:?YK_MODEL}" "$prompt" "$rules" "${YK_BUDGET:-1}" "$err" "${YK_SESSION:-}"
rc=$?
((rc == 0)) || { tail -5 "$err" >&2; exit "$rc"; }
