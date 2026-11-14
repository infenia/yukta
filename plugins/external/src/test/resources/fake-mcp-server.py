# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""A tiny MCP server over stdio for the tests: two pages of tools and a few behaviours."""
import json
import sys
import time

SCHEMA = {
    "type": "object",
    "required": ["text"],
    "properties": {
        "text": {"type": "string", "description": "What to echo"},
        "n": {"type": "integer", "default": 1},
        "mode": {"enum": ["a", "b"]},
        "flag": {"type": "boolean"},
        "items": {"type": "array"},
        "opts": {"type": "object"},
        "token": {"type": "string"},
        "odd": True,
    },
}
PAGES = [
    {"tools": [{"name": "echo", "inputSchema": SCHEMA}], "nextCursor": "2"},
    {"tools": [{"name": "text"}, {"name": "fail"}, {"name": "rpcerror"}, {"name": "slow"}, {"name": "exit"}]},
]

print("fake server starting", file=sys.stderr, flush=True)
for line in sys.stdin:
    req = json.loads(line)
    method, rid = req.get("method"), req.get("id")
    if rid is None:
        continue
    out = {"jsonrpc": "2.0", "id": rid}
    if method == "initialize":
        print("not json", flush=True)
        print(json.dumps({"jsonrpc": "2.0", "method": "notifications/message"}), flush=True)
        print(json.dumps({"jsonrpc": "2.0", "id": 9999, "result": {}}), flush=True)
        print(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "ping"}), flush=True)
        out["result"] = {"protocolVersion": "2025-06-18", "capabilities": {}}
    elif method == "tools/list":
        out["result"] = PAGES[1] if req["params"].get("cursor") == "2" else PAGES[0]
    elif method == "tools/call":
        name, args = req["params"]["name"], req["params"]["arguments"]
        if name == "echo":
            out["result"] = {"content": [], "structuredContent": args}
        elif name == "text":
            out["result"] = {"content": [{"type": "text", "text": "plain"}]}
        elif name == "fail":
            out["result"] = {"content": [{"type": "text", "text": "it broke"}], "isError": True}
        elif name == "rpcerror":
            out["error"] = {"code": -32000, "message": "bad call"}
        elif name == "slow":
            time.sleep(5)
            continue
        else:
            sys.exit(0)
    else:
        out["error"] = {"code": -32601, "message": "no method " + method}
    print(json.dumps(out), flush=True)
