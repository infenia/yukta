#!/usr/bin/env bash
# Use case: a chat agent that keeps per-user history. SESSION_MEMORY loads the history,
# LLM_CALL answers, SESSION_MEMORY appends the turn. Needs an OpenAI-compatible endpoint.
#
#   LLM_BASE_URL  default http://localhost:11434/v1 (Ollama)
#   LLM_MODEL     default llama3.2
#   LLM_API_KEY   default "ollama" (Ollama ignores it; set a real key for hosted APIs). The session
#                 refers to it as decrypted:llm-api-key, so start the server with
#                 YUKTA_SECRET_LLM_API_KEY set to the same value: a definition cannot hold a key.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
LLM_BASE_URL="${LLM_BASE_URL:-http://localhost:11434/v1}"
LLM_MODEL="${LLM_MODEL:-llama3.2}"
LLM_API_KEY="${LLM_API_KEY:-ollama}"

require_server
require_plugin LLM_CALL
require_plugin SESSION_MEMORY
if ! curl -s -m 3 -o /dev/null "$LLM_BASE_URL/models" -H "Authorization: Bearer $LLM_API_KEY"; then
  echo "SKIP: no OpenAI-compatible endpoint at $LLM_BASE_URL (set LLM_BASE_URL / LLM_MODEL / LLM_API_KEY)"
  exit 3
fi

# Write a copy of the session with the endpoint settings applied, so nothing secret or
# environment-specific is committed.
work=$(mktemp -d)
jq --arg url "$LLM_BASE_URL" --arg model "$LLM_MODEL" --arg key "$LLM_API_KEY" '
  (.workflows[].nodes[] | select(.type == "LLM_CALL").config) |= (.baseUrl = $url | .model = $model | .apiKey = $key)
' session.json > "$work/session.json"

apply_session "$work/session.json"
run_workflow "$work" turn-1 SUCCESS "Nice to meet you"
run_workflow "$work" turn-2 SUCCESS "Your name is Ada"
