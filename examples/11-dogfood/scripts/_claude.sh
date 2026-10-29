#!/usr/bin/env bash
# The one launcher of every headless Claude of the dev loop (the fixers and the implementer). Sourced.
#
# yk_claude MODE PROMPT RULES_FILE BUDGET OUT [SESSION_ID]
#   MODE        haiku, sonnet or opus: a new session (SESSION_ID names it, so its transcript can be found later)
#   RULES_FILE  the system prompt, one constant file per role, so the cached prefix is byte-identical
#   OUT         file that collects claude's stderr
# $YK_EFFORT (low, medium, high), when set, is the effort of the session. $YK_ADVISOR (sonnet, opus, ...), when set, is its advisor
# model (claude --advisor: experimental, Anthropic API only); it stays off otherwise.
# The answer is claude's JSON result on stdout; $YK_SCHEMA is the JSON schema of its final answer.
#
# $YK_MCP_URL and $YK_MCP_KEY, when both are set, give the session the Yukta server's MCP endpoint, read tools only (get_node_output,
# get_execution_tree, ...): the key should be a VIEWER key, so the server refuses a change whatever the prompt says, and the mutating
# tools are denied here as well. The key reaches claude by environment, never by argument.
#
# Slim on purpose: none of the user's plugins, skills or settings and no MCP server but that one (their tool schemas cost more
# than a fix), a fixed tool list and nothing that varies per run in the system prompt. Not --bare: it would
# also drop the login. The project hooks still load (they keep the gradle guard); YUKTA_HEAL_FIXER=1 lets
# the session call ./gradlew directly. It never commits or pushes: the workflow does.

# A headless claude must not think it is the session that started the server.
_yk_unset=(CLAUDECODE CLAUDE_CODE_SESSION_ID CLAUDE_CODE_ENTRYPOINT CLAUDE_CODE_SSE_PORT
  CLAUDE_CODE_MESSAGING_SOCKET CLAUDE_CODE_MESSAGING_TOKEN CLAUDE_CODE_CHILD_SESSION
  CLAUDE_CODE_SESSION_ATTENDED CLAUDE_PID)
_yk_env_u=(); for _v in "${_yk_unset[@]}"; do _yk_env_u+=(-u "$_v"); done

# the read tools of the endpoint a fixer may call, and the tools that change a run or the server, denied even if the key allowed them
_yk_mcp_read=(get_node_output get_execution_tree get_execution_logs get_execution_result get_workflow_status get_workflow_history get_workflow_details)
_yk_mcp_deny=(start_workflow control_workflow control_node set_node_breakpoint signal_node rollback_workflow note_workflow_version
  set_scheduler_limits create_session)

yk_claude() {
  local mode="$1" prompt="$2" rules="$3" budget="$4" out="$5" sid="${6:-}" session model mcp=() allow=() deny=() t
  if [[ -n "${YK_MCP_URL:-}" && -n "${YK_MCP_KEY:-}" ]]; then
    mcp=(--mcp-config "$(jq -nc --arg u "$YK_MCP_URL" '{mcpServers: {yukta: {type: "http", url: $u, headers: {"X-API-Key": "${YK_MCP_KEY}"}}}}')")
    for t in "${_yk_mcp_read[@]}"; do allow+=("mcp__yukta__$t"); done
    for t in "${_yk_mcp_deny[@]}"; do deny+=("mcp__yukta__$t"); done
  fi
  model=(--model "$mode")
  [[ -z "${YK_EFFORT:-}" ]] || model+=(--effort "$YK_EFFORT")
  [[ -z "${YK_ADVISOR:-}" ]] || model+=(--advisor "$YK_ADVISOR")
  if [[ -n "$sid" ]]; then session=(--session-id "$sid"); else session=(--no-session-persistence); fi
  env "${_yk_env_u[@]}" YUKTA_HEAL_FIXER=1 "${YUKTA_CLAUDE_BIN:-claude}" -p "$prompt" "${model[@]}" "${session[@]}" \
    --setting-sources project --strict-mcp-config "${mcp[@]}" --disable-slash-commands \
    --exclude-dynamic-system-prompt-sections \
    --tools Read,Edit,Write,Grep,Glob,Bash --permission-mode auto --permission-prompts none \
    --allowedTools "Read" "Edit" "Write" "Grep" "Glob" "Bash(./gradlew:*)" "Bash(make:*)" "Bash(go test:*)" "Bash(go vet:*)" "Bash(node_modules/.bin/*:*)" "Bash(reuse:*)" "Bash(actionlint:*)" "Bash(mint:*)" "Bash(cd:*)" "${allow[@]}" \
    --disallowedTools "Bash(git commit:*)" "Bash(git push:*)" "${deny[@]}" \
    --append-system-prompt "$(cat "$rules")" --max-budget-usd "$budget" \
    --output-format json --json-schema "${YK_SCHEMA:?YK_SCHEMA is the answer schema}" 2>>"$out"
}
