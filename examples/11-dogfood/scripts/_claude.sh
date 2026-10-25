#!/usr/bin/env bash
# The one launcher of every headless Claude of the dev loop (the fixers and the implementer). Sourced.
#
# yk_claude MODE PROMPT RULES_FILE BUDGET OUT [SESSION_ID]
#   MODE        haiku, sonnet or opus: a new session (SESSION_ID names it, so a fixer can resume it later);
#               resume: continues the session $YUKTA_IMPL_SESSION (same model, warm cache, knows the change)
#   RULES_FILE  the system prompt, one constant file per role, so the cached prefix is byte-identical
#   OUT         file that collects claude's stderr
# The answer is claude's JSON result on stdout; $YK_SCHEMA is the JSON schema of its final answer.
#
# Slim on purpose: none of the user's plugins, skills, settings or MCP servers (their tool schemas cost more
# than a fix), a fixed tool list and nothing that varies per run in the system prompt. Method C adds one MCP server (a code
# index) through the issue's nav.json; nothing else of the user's setup. Not --bare: it would
# also drop the login. The project hooks still load (they keep the gradle guard); YUKTA_HEAL_FIXER=1 lets
# the session call ./gradlew directly. It never commits or pushes: the workflow does.

# A headless claude must not think it is the session that started the server.
_yk_unset=(CLAUDECODE CLAUDE_CODE_SESSION_ID CLAUDE_CODE_ENTRYPOINT CLAUDE_CODE_SSE_PORT
  CLAUDE_CODE_MESSAGING_SOCKET CLAUDE_CODE_MESSAGING_TOKEN CLAUDE_CODE_CHILD_SESSION
  CLAUDE_CODE_SESSION_ATTENDED CLAUDE_PID)
_yk_env_u=(); for _v in "${_yk_unset[@]}"; do _yk_env_u+=(-u "$_v"); done

yk_claude() {
  local mode="$1" prompt="$2" rules="$3" budget="$4" out="$5" sid="${6:-}" session model
  case "$mode" in
    resume) session=(--resume "${YUKTA_IMPL_SESSION:?resume needs YUKTA_IMPL_SESSION}") model=() ;;
    *) model=(--model "$mode")
       if [[ -n "$sid" ]]; then session=(--session-id "$sid"); else session=(--no-session-persistence); fi ;;
  esac
  # Method C: the issue's nav.json (next to implement.json, written by nav.sh) gives the implementer and its fixers the same MCP
  # config and tools, so a fixer that resumes the implementer sees the same tool definitions and the cache is not re-written.
  local nav=() navtools=() navf
  navf="$(dirname "${YUKTA_IMPL_COSTFILE:-/nonexistent/x}")/nav.json"
  if [[ -s "$navf" && "$(jq -r '.ok // false' "$navf" 2>/dev/null)" == true ]]; then
    [[ "$(jq -r '.mcp_config // ""' "$navf")" == "" ]] || nav=(--mcp-config "$(jq -r .mcp_config "$navf")")
    mapfile -t navtools < <(jq -r '.tools[]?' "$navf")
  fi
  env "${_yk_env_u[@]}" YUKTA_HEAL_FIXER=1 "${YUKTA_CLAUDE_BIN:-claude}" -p "$prompt" "${model[@]}" "${session[@]}" \
    --setting-sources project --strict-mcp-config "${nav[@]}" --disable-slash-commands \
    --exclude-dynamic-system-prompt-sections \
    --tools Read,Edit,Write,Grep,Glob,Bash --permission-mode acceptEdits \
    --allowedTools "Read" "Edit" "Write" "Grep" "Glob" "Bash(./gradlew:*)" "Bash(codegraph:*)" "${navtools[@]}" \
    --disallowedTools "Bash(git commit:*)" "Bash(git push:*)" \
    --append-system-prompt "$(cat "$rules")" --max-budget-usd "$budget" \
    --output-format json --json-schema "${YK_SCHEMA:?YK_SCHEMA is the answer schema}" 2>>"$out"
}
