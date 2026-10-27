#!/usr/bin/env bash
# The step of the `claude` workflow: one headless Claude Code session. The message on stdin is {"prompt": "..."}; the
# answer is claude's JSON result on stdout; a non-zero exit is the workflow's failure port.
#
# Environment (a value of "-" is unset; the workflow cannot pass an empty one):
#   YK_MODEL haiku|sonnet|opus   YK_EFFORT low|medium|high   YK_BUDGET USD   YK_RULES system prompt file   YK_SCHEMA JSON schema of the answer
#   YK_SESSION session id   YK_ADVISOR advisor model (experimental, off when unset)   YK_CLAUDE_BIN a stand-in for `claude` (tests)
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for v in YK_MODEL YK_EFFORT YK_BUDGET YK_RULES YK_SCHEMA YK_SESSION YK_CLAUDE_BIN YK_ADVISOR; do [[ "${!v:-}" == - ]] && unset "$v"; done
prompt="$(jq -r '.prompt // empty')"
[[ -n "$prompt" ]] || { echo "claude.sh: no prompt on stdin" >&2; exit 2; }
# shellcheck source=_claude.sh
source "$here/_claude.sh"
# A fixer does not read the project's CLAUDE.md (about 5k tokens on every attempt, measured): fixer-rules.md has the few rules it needs.
export CLAUDE_CODE_DISABLE_CLAUDE_MDS=1
# The aliases move when a new model ships; a fixer's cost and behaviour must not.
export ANTHROPIC_DEFAULT_HAIKU_MODEL=claude-haiku-5-5 ANTHROPIC_DEFAULT_SONNET_MODEL=claude-sonnet-5-5
[[ -z "${YK_CLAUDE_BIN:-}" ]] || export YUKTA_CLAUDE_BIN="$YK_CLAUDE_BIN"
err="$(mktemp)"
trap 'rm -f "$err"' EXIT
yk_claude "${YK_MODEL:?YK_MODEL}" "$prompt" "${YK_RULES:?YK_RULES}" "${YK_BUDGET:-1}" "$err" "${YK_SESSION:-}"
rc=$?
((rc == 0)) || { tail -5 "$err" >&2; exit "$rc"; }
