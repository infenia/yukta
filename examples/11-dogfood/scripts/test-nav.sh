#!/usr/bin/env bash
# Offline test of the navigation backends of method C (nav.sh, and how yk_claude hands them to a session): a fake
# codebase-memory-mcp, a fake codegraph and a stub of the IDE's MCP server stand in for the real ones. No server, no Claude, no
# IDE window, no spend.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
pids=()
trap 'kill "${pids[@]}" 2>/dev/null || true; rm -rf "$dir"' EXIT
mkdir -p "$dir/bin" "$dir/wt" "$dir/home"
export HOME="$dir/home" YUKTA_DEV_HOME="$dir/dev" PATH="$dir/bin:$PATH" YUKTA_NAV_CBM_CACHE="$dir/cbm-cache" YUKTA_NAV_IDE_POLL=0
echo '{"mcpServers":{}}' >"$HOME/.claude.json"
before="$(sha256sum "$HOME/.claude.json" | cut -c1-16)"
nav="$here/nav.sh"

# ---- fakes
cat >"$dir/cbm" <<'SH'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")" && pwd)"
[[ "$1" == --version ]] && { echo "codebase-memory-mcp 9.9.9"; exit 0; }
echo "$* | CBM_CACHE_DIR=${CBM_CACHE_DIR:-}" >>"$here/cbm.log"
[[ -e "$here/cbm-fails" ]] && { echo '{"error":"disk full"}'; exit 1; }
echo '{"project":"proj-of-the-worktree","status":"indexed"}'
SH
cat >"$dir/bin/codegraph" <<'SH'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")/.." && pwd)"
case "$1" in
  --version) echo "1.2.3" ;;
  init) echo "init $PWD" >>"$here/codegraph.log"; [[ -e "$here/codegraph-fails" ]] && exit 1; mkdir -p .codegraph; echo ok >.codegraph/index ;;
  status) [[ -e .codegraph/index ]] && echo "Indexed: 10 files" || echo "Not initialized" ;;
esac
SH
chmod +x "$dir/cbm" "$dir/bin/codegraph"
export YUKTA_NAV_CBM_BIN="$dir/cbm"
# the IDE's MCP server: initialize, tools/call (ide_project_status opens a project, ide_index_status answers indexing, then ready)
cat >"$dir/ide.py" <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
portfile, log, ready_after = sys.argv[1], open(sys.argv[2], "a"), int(sys.argv[3])
polls = [0]
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("content-length", 0))) or b"{}")
        m = body.get("method")
        if m == "initialize":
            res = {"protocolVersion": "2025-03-26", "serverInfo": {"name": "intellij-index", "version": "stub"}, "capabilities": {}}
        elif m == "tools/call":
            n, a = body["params"]["name"], body["params"]["arguments"]
            log.write(json.dumps({"tool": n, "args": a}) + "\n"); log.flush()
            if n == "ide_index_status":
                polls[0] += 1
                text = "Index ready (smart mode)" if polls[0] > ready_after else "Indexing in progress (dumb mode)"
            else:
                text = "project opened"
            res = {"content": [{"type": "text", "text": text}], "isError": False}
        else:
            res = {}
        out = json.dumps({"jsonrpc": "2.0", "id": body.get("id"), "result": res}).encode()
        self.send_response(200); self.send_header("content-type", "application/json"); self.send_header("content-length", str(len(out))); self.end_headers(); self.wfile.write(out)
s = HTTPServer(("127.0.0.1", 0), H)
open(portfile, "w").write(str(s.server_port))
s.serve_forever()
PY
start_ide() { # READY_AFTER NAME VAR: starts a stub in this shell (so the trap can kill it) and sets VAR to its URL
  python3 -I "$dir/ide.py" "$dir/$2.port" "$dir/$2.log" "$1" >/dev/null 2>&1 &  # detached from any pipe
  pids+=($!)
  for _ in $(seq 50); do [[ -s "$dir/$2.port" ]] && break; sleep 0.1; done
  printf -v "$3" 'http://127.0.0.1:%s/index-mcp/streamable-http' "$(cat "$dir/$2.port")"
}
start_ide 2 ide-ok ide_ok
start_ide 999999 ide-never ide_never

# ---- probe: the IDE is opt-in, the others come in the default order
p="$(YUKTA_NAV_IDE_URL="$ide_ok" "$nav" probe)"
[[ "$(jq -c .backends <<<"$p")" == '["cbm","codegraph"]' ]] || fail "default probe: $p"
jq -e '.skipped[0].backend == "ide" and (.skipped[0].reason | test("YUKTA_NAV_IDE=open"))' <<<"$p" >/dev/null || fail "the IDE skip reason: $p"
p="$(YUKTA_NAV_IDE=open YUKTA_NAV_IDE_URL="$ide_ok" "$nav" probe)"
[[ "$(jq -c .backends <<<"$p")" == '["ide","cbm","codegraph"]' ]] || fail "opted-in probe: $p"
p="$(YUKTA_NAV_IDE=open YUKTA_NAV_IDE_URL="http://127.0.0.1:1/x" "$nav" probe)"
jq -e '(.backends | index("ide") | not) and (.skipped[0].reason | test("does not answer"))' <<<"$p" >/dev/null || fail "an IDE that does not answer: $p"
p="$(YUKTA_NAV_CBM_BIN="$dir/missing" "$nav" probe)"
[[ "$(jq -c .backends <<<"$p")" == '["codegraph"]' ]] || fail "no cbm: $p"
echo "ok   probe"

# ---- prepare with codebase-memory-mcp: the index, a per-run MCP config, read-only tools, constant rules
st="$dir/state1"
out="$("$nav" prepare "$dir/wt" "$st")" || fail "prepare failed: $out"
grep -q '^NAV backend=cbm ok=true' <<<"$out" || fail "output: $out"
n="$st/nav.json"
[[ "$(jq -r .backend "$n")" == cbm && "$(jq -r .ok "$n")" == true && "$(jq -r .version "$n")" == 9.9.9 ]] || fail "nav.json: $(jq -c . "$n")"
jq -e '.index_seconds | type == "number"' "$n" >/dev/null || fail "no index time"
grep -q "index_repository .*\"repo_path\":\"$dir/wt\".* | CBM_CACHE_DIR=$dir/cbm-cache" "$dir/cbm.log" || fail "the worktree was not indexed with its own cache: $(cat "$dir/cbm.log")"
[[ "$(jq -r '.mcpServers["codebase-memory-mcp"].command' "$st/nav-mcp.json")" == "$dir/cbm" ]] || fail "mcp config: $(cat "$st/nav-mcp.json")"
[[ "$(jq -r '.mcpServers["codebase-memory-mcp"].env.CBM_CACHE_DIR' "$st/nav-mcp.json")" == "$dir/cbm-cache" ]] || fail "mcp cache dir"
[[ "$(jq '.tools | length' "$n")" == 11 ]] || fail "tools: $(jq -c .tools "$n")"
jq -e '.tools | all(startswith("mcp__codebase-memory-mcp__")) and (map(test("index_repository|delete_project|manage_adr|ingest_traces")) | any | not)' "$n" >/dev/null || fail "the tools must be the query tools only: $(jq -c .tools "$n")"
grep -q 'proj-of-the-worktree' "$n" || fail "the note does not name the project"
head -c "$(wc -c <"$here/../implementer-rules.md")" "$st/nav-rules.md" | cmp -s - "$here/../implementer-rules.md" || fail "the rules do not start with the implementer's rules"
grep -q 'code knowledge graph' "$st/nav-rules.md" || fail "no cbm paragraph in the rules"
out2="$("$nav" prepare "$dir/wt" "$dir/state1b")"
cmp -s "$st/nav-rules.md" "$dir/state1b/nav-rules.md" || fail "the rules differ between two runs: the cached prefix would not be stable"
[[ "$(sha256sum "$HOME/.claude.json" | cut -c1-16)" == "$before" ]] || fail "the user's Claude configuration was touched"
echo "ok   codebase-memory-mcp"

# ---- fallback: cbm fails, CodeGraph takes over; with both failing the run goes on without an index
touch "$dir/cbm-fails"
st="$dir/state2"
out="$("$nav" prepare "$dir/wt" "$st")" || fail "prepare failed"
grep -q 'backend=codegraph ok=true' <<<"$out" && grep -q 'cbm: codebase-memory-mcp did not index' <<<"$out" || fail "fallback output: $out"
n="$st/nav.json"
[[ "$(jq -r .backend "$n")" == codegraph && "$(jq -r .mcp_config "$n")" == null && "$(jq -c .tools "$n")" == "[]" ]] || fail "codegraph nav.json: $(jq -c . "$n")"
[[ "$(jq -r '.attempts | map(.backend + ":" + (.ok | tostring)) | join(",")' "$n")" == "ide:false,cbm:false,codegraph:true" ]] || fail "attempts: $(jq -c .attempts "$n")"
grep -q 'codegraph explore' "$st/nav-rules.md" && [[ ! -e "$st/nav-mcp.json" ]] || fail "codegraph rules or a stray mcp config"
touch "$dir/codegraph-fails"
st="$dir/state3"
out="$("$nav" prepare "$dir/wt" "$st")" && rc=0 || rc=$?
[[ "$rc" == 0 ]] || fail "prepare must not fail the run when no backend works"
n="$st/nav.json"
[[ "$(jq -r .backend "$n")" == none && "$(jq -r .ok "$n")" == false && "$(jq -r .rules "$n")" == null && ! -e "$st/nav-rules.md" ]] || fail "no backend: $(jq -c . "$n")"
rm -f "$dir/cbm-fails" "$dir/codegraph-fails"
echo "ok   fallback"

# ---- the IDE backend: opt-in, opens the project, waits for the index, read-only tools, a timeout falls through
st="$dir/state4"
out="$(YUKTA_NAV_IDE=open YUKTA_NAV_IDE_URL="$ide_ok" "$nav" prepare "$dir/wt" "$st")" || fail "prepare failed"
grep -q 'backend=ide ok=true' <<<"$out" || fail "ide output: $out"
n="$st/nav.json"
jq -e --arg u "$ide_ok" '.mcp_config | . != null' "$n" >/dev/null || fail "no mcp config"
[[ "$(jq -r '.mcpServers["intellij-index"].url' "$st/nav-mcp.json")" == "$ide_ok" && "$(jq -r '.mcpServers["intellij-index"].type' "$st/nav-mcp.json")" == http ]] || fail "ide mcp config: $(cat "$st/nav-mcp.json")"
jq -e '.tools | length == 13 and all(startswith("mcp__intellij-index__ide_")) and (map(test("refactor|move_file")) | any | not)' "$n" >/dev/null || fail "ide tools must be a read-only set: $(jq -c .tools "$n")"
grep -q "\"tool\": \"ide_project_status\", \"args\": {\"project_path\": \"$dir/wt\"}" "$dir/ide-ok.log" || fail "the IDE was not asked to open the worktree: $(cat "$dir/ide-ok.log")"
[[ "$(grep -c ide_index_status "$dir/ide-ok.log")" -ge 3 ]] || fail "the index status was not polled until ready: $(cat "$dir/ide-ok.log")"
grep -q "project_path=\\\\\"$dir/wt\\\\\"\|project_path=\"$dir/wt\"" "$n" || fail "the note does not name the project path: $(jq -r .prompt_note "$n")"
grep -q 'IDE.s index' "$st/nav-rules.md" || fail "no ide paragraph in the rules"
st="$dir/state5"
out="$(YUKTA_NAV_IDE=open YUKTA_NAV_IDE_URL="$ide_never" YUKTA_NAV_INDEX_TIMEOUT=1 "$nav" prepare "$dir/wt" "$st")" || fail "prepare failed"
grep -q 'backend=cbm ok=true' <<<"$out" && grep -q 'ide: the IDE did not finish indexing' <<<"$out" || fail "an IDE that never finishes must fall through to cbm: $out"
echo "ok   ide"

# ---- yk_claude gives a session the MCP config and the tools of nav.json, and nothing when there is none
cat >"$dir/claude" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$@" >"$(dirname "$0")/argv.txt"
echo '{"total_cost_usd":0,"structured_output":{"fixed":true,"summary":"x"}}'
SH
chmod +x "$dir/claude"
echo "base rules" >"$dir/rules.md"
call() { # STATE: yk_claude as an implementer or fixer of the issue whose state directory is STATE
  ( source "$here/_claude.sh"; export YUKTA_CLAUDE_BIN="$dir/claude" YUKTA_IMPL_COSTFILE="$1/implement.json" YUKTA_IMPL_SESSION=00000000-0000-0000-0000-000000000000
    YK_SCHEMA='{"type":"object"}'; yk_claude resume "prompt" "$dir/rules.md" 1 "$dir/out.err" >/dev/null )
}
call "$dir/state1"
grep -qx -- '--strict-mcp-config' "$dir/argv.txt" || fail "strict MCP config must stay on"
grep -qx -- '--mcp-config' "$dir/argv.txt" && grep -qx "$dir/state1/nav-mcp.json" "$dir/argv.txt" || fail "no --mcp-config for a method C issue: $(tr '\n' ' ' <"$dir/argv.txt")"
grep -qx 'mcp__codebase-memory-mcp__search_graph' "$dir/argv.txt" || fail "the tools are not allowed: $(tr '\n' ' ' <"$dir/argv.txt")"
! grep -qx 'mcp__codebase-memory-mcp__index_repository' "$dir/argv.txt" || fail "a mutating tool was allowed"
call "$dir/state2"   # codegraph: no MCP config, nothing extra
! grep -qx -- '--mcp-config' "$dir/argv.txt" || fail "codegraph must not add an MCP config"
call "$dir/state3"   # no backend
! grep -qx -- '--mcp-config' "$dir/argv.txt" && ! grep -q 'mcp__' "$dir/argv.txt" || fail "a run without a backend got navigation tools"
mkdir -p "$dir/plain"; call "$dir/plain"   # a plain run: no nav.json at all
! grep -qx -- '--mcp-config' "$dir/argv.txt" || fail "a plain run got an MCP config"
echo "ok   yk_claude"
echo "ok   nav"
