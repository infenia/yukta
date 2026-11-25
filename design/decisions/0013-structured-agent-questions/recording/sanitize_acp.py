#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Clean a raw ACP recording (frames already {t, dir, frame}): sanitize_acp.py RAW.jsonl CLEAN.jsonl

The working directory becomes /work and the session id a fixed one; the model list of the
session/new answer is cut to its first entry. Nothing else is changed.
"""
import json, re, sys
src, dst, sess = sys.argv[1], sys.argv[2], "00000000-0000-4000-8000-000000000001"
ids = {"sid": None}
def clean(o):
    if isinstance(o, dict):
        return {k: clean(v) for k, v in o.items()}
    if isinstance(o, list):
        return [clean(v) for v in o]
    if isinstance(o, str):
        o = re.sub(r"/tmp/claude-1000/\S*?/rec/[\w-]+", "/work", o)
        return sess if o == ids["sid"] else o
    return o
out = []
for l in open(src):
    r = json.loads(l)
    f = r["frame"]
    res = f.get("result") if isinstance(f.get("result"), dict) else {}
    if ids["sid"] is None and res.get("sessionId"):
        ids["sid"] = res["sessionId"]
    if isinstance(res.get("models"), dict):
        res["models"]["availableModels"] = res["models"].get("availableModels", [])[:1]
    out.append({"t": r["t"], "dir": r["dir"], "frame": clean(f)})
with open(dst, "w") as f:
    for r in out:
        f.write(json.dumps(r, separators=(",", ":")) + "\n")
print(len(out), "frames")
