#!/usr/bin/env bash
# Code navigation for the issue runs of the improvement loop (method C): index an issue worktree with the first backend that works, so the
# implementer and its fixers can search the code instead of reading files one by one.
#
#   nav.sh probe                        which backends could run now, in order, and why the others are skipped (JSON)
#   nav.sh prepare <worktree> <state>   index the worktree; writes <state>/nav.json and <state>/nav-rules.md. Never fails
#                                       the run: with no working backend nav.json says backend "none", ok false
#
# Backends, tried in the order of YUKTA_NAV_BACKENDS (default ide,cbm,codegraph):
#   ide        the IDE's index MCP server (intellij-index). Only with YUKTA_NAV_IDE=open: it makes the IDE open the worktree as a
#              project (a new window and a fresh index per issue), so it is opt-in. A read-only subset of its tools is allowed.
#              YUKTA_NAV_IDE_URL (default http://127.0.0.1:29170/index-mcp/streamable-http)
#   cbm        codebase-memory-mcp, the binary on your PATH (YUKTA_NAV_CBM_BIN); not pinned, its version is recorded. Its index
#              lives in YUKTA_NAV_CBM_CACHE (default $DEV_HOME/cbm, about 120 MB per worktree; safe to delete). It is given to
#              the sessions with --mcp-config, so your Claude configuration is not touched.
#   codegraph  CodeGraph: codegraph init in the worktree (about 8 minutes on this repository), used through Bash.
# YUKTA_NAV_INDEX_TIMEOUT (seconds, 1500) bounds the indexing of one backend; the time it took is recorded as index_seconds
# and counts as part of the cost of method C.
#
# nav.json: {backend, ok, index_seconds, version, mcp_config, tools, rules, prompt_note, attempts: [{backend, ok, reason, seconds}]}.
# yk_claude (_claude.sh) reads it to give the implementer and the fixers the same MCP config and tools.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/_lib.sh"
need jq
IDE_URL="${YUKTA_NAV_IDE_URL:-http://127.0.0.1:29170/index-mcp/streamable-http}"
CBM_CACHE="${YUKTA_NAV_CBM_CACHE:-$DEV_HOME/cbm}"
TIMEOUT="${YUKTA_NAV_INDEX_TIMEOUT:-1500}"
IDE_TOOLS=(ide_find_references ide_find_definition ide_find_implementations ide_find_class ide_find_file ide_search_text
  ide_call_hierarchy ide_type_hierarchy ide_find_super_methods ide_diagnostics ide_index_status ide_project_status ide_sync_files)
CBM_TOOLS=(search_graph query_graph trace_path get_code_snippet get_file_outline get_graph_schema get_architecture search_code
  list_projects index_status detect_changes)

backends() { local IFS=,; for b in ${YUKTA_NAV_BACKENDS:-ide,cbm,codegraph}; do echo "$b"; done; }
cbm_bin() { echo "${YUKTA_NAV_CBM_BIN:-$(command -v codebase-memory-mcp 2>/dev/null || echo "$HOME/.local/bin/codebase-memory-mcp")}"; }

# ---- IDE helpers: one JSON-RPC POST per call (the server needs no session)
mcp_post() { # BODY [SECONDS]
  curl -s -m "${2:-10}" -X POST -H 'content-type: application/json' -H 'accept: application/json, text/event-stream' -d "$1" "$IDE_URL" 2>/dev/null
}
mcp_json() { sed -n 's/^data: //p; /^{/p' | head -1; }
ide_call() { # TOOL ARGS-JSON -> the JSON-RPC response
  mcp_post "$(jq -nc --arg t "$1" --argjson a "$2" '{jsonrpc: "2.0", id: 3, method: "tools/call", params: {name: $t, arguments: $a}}')" 30 | mcp_json
}
ide_reachable() {
  mcp_post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"yukta-nav","version":"0"}}}' 4 \
    | mcp_json | jq -e '.result.serverInfo.name' >/dev/null 2>&1
}

# why_not BACKEND: empty when the backend could run, else the reason it cannot
why_not() {
  case "$1" in
    ide)
      [[ "${YUKTA_NAV_IDE:-}" == open ]] || { echo "not enabled (YUKTA_NAV_IDE=open lets the IDE open each worktree as a project)"; return; }
      ide_reachable || echo "the IDE index server does not answer at $IDE_URL" ;;
    cbm)
      [[ -x "$(cbm_bin)" ]] || echo "codebase-memory-mcp is not installed ($(cbm_bin))" ;;
    codegraph)
      command -v codegraph >/dev/null 2>&1 || echo "codegraph is not installed" ;;
    *) echo "unknown backend" ;;
  esac
}

cmd_probe() {
  local avail=() skipped='[]' b why
  while read -r b; do
    why="$(why_not "$b")"
    if [[ -z "$why" ]]; then avail+=("$b"); else skipped="$(jq -c --arg b "$b" --arg w "$why" '. + [{backend: $b, reason: $w}]' <<<"$skipped")"; fi
  done < <(backends)
  jq -nc --argjson a "$(printf '%s\n' "${avail[@]:-}" | jq -R . | jq -sc 'map(select(. != ""))')" --argjson s "$skipped" '{backends: $a, skipped: $s}'
}

# ---- preparing a backend: each sets MCP_CFG, TOOLS (JSON array), NOTE, VERSION and returns 0, or sets REASON and returns 1
try_ide() { # WORKTREE STATE
  local wt="$1" st="$2" deadline text
  MCP_CFG="$st/nav-mcp.json" VERSION="$(mcp_post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"yukta-nav","version":"0"}}}' 4 | mcp_json | jq -r '.result.serverInfo.version // ""')"
  # asking for the project opens it in the IDE; then wait until the IDE says its index is ready
  ide_call ide_project_status "$(jq -nc --arg p "$wt" '{project_path: $p}')" | jq -e '.result and (.result.isError | not)' >/dev/null 2>&1 \
    || { REASON="the IDE would not open $wt as a project"; return 1; }
  deadline=$(($(date +%s) + TIMEOUT))
  while :; do
    text="$(ide_call ide_index_status "$(jq -nc --arg p "$wt" '{project_path: $p}')" | jq -r '[.result.content[]?.text] | join(" ")' 2>/dev/null)"
    if [[ -n "$text" ]] && grep -qiE 'ready|smart' <<<"$text" && ! grep -qiE 'dumb|indexing|scanning|not ready' <<<"$text"; then break; fi
    (($(date +%s) >= deadline)) && { REASON="the IDE did not finish indexing $wt in ${TIMEOUT}s"; return 1; }
    sleep "${YUKTA_NAV_IDE_POLL:-5}"
  done
  jq -nc --arg u "$IDE_URL" '{mcpServers: {"intellij-index": {type: "http", url: $u}}}' >"$MCP_CFG"
  TOOLS="$(printf '%s\n' "${IDE_TOOLS[@]}" | jq -R '"mcp__intellij-index__" + .' | jq -sc .)"
  NOTE="A code index of this worktree is available in the IDE through the mcp__intellij-index__ide_* tools (read-only subset). Always pass project_path=\"$wt\". After you edit files, call ide_sync_files before you search again."
}

try_cbm() {
  local wt="$1" st="$2" bin out
  bin="$(cbm_bin)"
  MCP_CFG="$st/nav-mcp.json" VERSION="$("$bin" --version 2>/dev/null | awk '{print $NF}')"
  mkdir -p "$CBM_CACHE"
  out="$(CBM_CACHE_DIR="$CBM_CACHE" timeout "$TIMEOUT" "$bin" cli --quiet index_repository "$(jq -nc --arg p "$wt" '{repo_path: $p}')" 2>/dev/null | tail -1)"
  [[ "$(jq -r '.status // ""' <<<"$out" 2>/dev/null)" == indexed ]] || { REASON="codebase-memory-mcp did not index $wt: $(jq -r '.error // empty' <<<"$out" 2>/dev/null | cut -c1-120)"; return 1; }
  PROJECT="$(jq -r .project <<<"$out")"
  jq -nc --arg b "$bin" --arg c "$CBM_CACHE" '{mcpServers: {"codebase-memory-mcp": {type: "stdio", command: $b, args: [], env: {CBM_CACHE_DIR: $c}}}}' >"$MCP_CFG"
  TOOLS="$(printf '%s\n' "${CBM_TOOLS[@]}" | jq -R '"mcp__codebase-memory-mcp__" + .' | jq -sc .)"
  NOTE="A code index of this worktree is available through the mcp__codebase-memory-mcp__* tools (search_graph, trace_path, get_code_snippet, search_code, get_architecture, ...). Always pass project=\"$PROJECT\". The index describes the code as it was when you started: read the files you edit directly."
}

try_codegraph() {
  local wt="$1"
  MCP_CFG="" TOOLS='[]' VERSION="$(codegraph --version 2>/dev/null | head -1)"
  (cd "$wt" && timeout "$TIMEOUT" codegraph init >/dev/null 2>&1) || { REASON="codegraph init failed or took more than ${TIMEOUT}s in $wt"; return 1; }
  (cd "$wt" && codegraph status 2>&1 | grep -qi 'not initialized') && { REASON="codegraph init left no index in $wt"; return 1; }
  NOTE="A CodeGraph index of this worktree is ready: run codegraph explore \"<symbols or question>\" once before reading files one by one (codegraph node <name> shows one symbol). The index describes the code as it was when you started."
}

cmd_prepare() {
  local wt="${1:?usage: nav.sh prepare <worktree> <state-dir>}" st="${2:?usage: nav.sh prepare <worktree> <state-dir>}" b why start secs
  local attempts='[]' chosen="" MCP_CFG="" TOOLS='[]' NOTE="" VERSION="" PROJECT="" REASON=""
  mkdir -p "$st"
  rm -f "$st/nav.json" "$st/nav-mcp.json" "$st/nav-rules.md"
  while read -r b; do
    why="$(why_not "$b")"
    if [[ -n "$why" ]]; then attempts="$(jq -c --arg b "$b" --arg w "$why" '. + [{backend: $b, ok: false, reason: $w, seconds: 0}]' <<<"$attempts")"; continue; fi
    start="$(date +%s)"
    REASON=""
    if "try_$b" "$wt" "$st"; then
      secs=$(($(date +%s) - start))
      attempts="$(jq -c --arg b "$b" --argjson s "$secs" '. + [{backend: $b, ok: true, reason: "", seconds: $s}]' <<<"$attempts")"
      chosen="$b"
      break
    fi
    secs=$(($(date +%s) - start))
    attempts="$(jq -c --arg b "$b" --arg w "$REASON" --argjson s "$secs" '. + [{backend: $b, ok: false, reason: $w, seconds: $s}]' <<<"$attempts")"
    rm -f "$st/nav-mcp.json"
  done < <(backends)
  if [[ -n "$chosen" ]]; then
    cat "$here/../implementer-rules.md" "$here/../nav/rules-$chosen.md" >"$st/nav-rules.md"
    jq -n --arg b "$chosen" --argjson secs "$(jq -r --arg b "$chosen" '[.[] | select(.backend == $b)] | last | .seconds' <<<"$attempts")" --arg v "$VERSION" \
      --arg cfg "$([[ -n "$MCP_CFG" ]] && echo "$MCP_CFG")" --argjson tools "$TOOLS" --arg rules "$st/nav-rules.md" --arg note "$NOTE" --argjson attempts "$attempts" \
      '{backend: $b, ok: true, index_seconds: $secs, version: $v, mcp_config: (if $cfg == "" then null else $cfg end), tools: $tools, rules: $rules, prompt_note: $note, attempts: $attempts}' >"$st/nav.json"
  else
    jq -n --argjson attempts "$attempts" '{backend: "none", ok: false, index_seconds: ($attempts | map(.seconds) | add // 0), version: "", mcp_config: null, tools: [], rules: null, prompt_note: "", attempts: $attempts}' >"$st/nav.json"
  fi
  echo "NAV $(jq -r '"backend=\(.backend) ok=\(.ok) index_seconds=\(.index_seconds)"' "$st/nav.json")$(jq -r '[.attempts[] | select(.ok | not) | "\(.backend): \(.reason)"] | if length > 0 then " (skipped: " + join("; ") + ")" else "" end' "$st/nav.json")"
  exit 0
}

case "${1:-}" in
  probe) cmd_probe ;;
  prepare) shift; cmd_prepare "$@" ;;
  *) sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; exit 2 ;;
esac
