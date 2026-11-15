#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Clean a raw recording (see README.md): sanitize.py RAW.jsonl CLEAN.jsonl"""
import json, re, sys
src, dst, sess = sys.argv[1], sys.argv[2], "00000000-0000-4000-8000-000000000001"
WORK = "/work"
DROP_SYSTEM = {"hook_started", "hook_response", "ui_log", "thinking_tokens"}
def clean(o):
    if isinstance(o, dict):
        return {k: clean(v) for k, v in o.items()}
    if isinstance(o, list):
        return [clean(v) for v in o]
    if isinstance(o, str):
        o = re.sub(r"/tmp/claude-1000/\S*?/rec/work", WORK, o)
        o = re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", lambda m: sess if m.group(0) == ids["sid"] else m.group(0), o)
        return o
    return o
ids = {"sid": None}
out = []
for l in open(src):
    r = json.loads(l)
    if r["dir"] == "err":
        continue
    try:
        o = json.loads(r["line"])
    except ValueError:
        continue
    if ids["sid"] is None and o.get("session_id"):
        ids["sid"] = o["session_id"]
    t, st = o.get("type"), o.get("subtype")
    if t == "rate_limit_event" or (t == "system" and st in DROP_SYSTEM):
        continue
    if t == "system" and st == "init":
        o = {k: o[k] for k in ("type", "subtype", "cwd", "session_id", "model", "permissionMode") if k in o}
    if t == "result":
        for k in ("usage", "modelUsage", "total_cost_usd", "duration_api_ms", "fast_mode_state",
                  "fast_mode_disabled_reason", "subagent_stats", "uuid"):
            o.pop(k, None)
    o.pop("uuid", None)
    out.append({"t": r["t"], "dir": r["dir"], "frame": clean(o)})
with open(dst, "w") as f:
    for r in out:
        f.write(json.dumps(r, separators=(",", ":")) + "\n")
print(len(out), "frames")
