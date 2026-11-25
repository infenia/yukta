#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Record whether an Agent Client Protocol agent asks a structured question.

The client declares form elicitation (`clientCapabilities.elicitation.form = {}`, ACP schema v1),
then prompts the agent to ask the user a question. An `elicitation/create` is answered as the
scenario says; a permission request is allowed; anything else is refused. Every line in both directions is written as {"t", "dir", "frame"}. Nothing is invented: if
the agent asks in plain text instead, that is what the recording shows.

Scenarios:
  single   one option picked (the second), its free-text field left out
  multi    a multi-select question, the first two options picked
  other    only the free-text field, no option
  decline  the user declines (`action: decline`)
  nocap    no elicitation capability declared (what Yukta sends today, ADR 0004 D13)

Usage: record_acp_question.py SCENARIO OUT.jsonl WORKDIR -- agy acp
"""
import json, os, queue, subprocess, sys, threading, time

scenario, out_path, workdir = sys.argv[1], sys.argv[2], os.path.abspath(sys.argv[3])
cmd = sys.argv[sys.argv.index("--") + 1:]
p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                     text=True, cwd=workdir, bufsize=1)
log = open(out_path, "w")
t0 = time.time()
inbox = queue.Queue()
next_id = [0]
seen = {"elicitation": 0, "permission": 0, "other": []}


def rec(direction, frame):
    log.write(json.dumps({"t": round(time.time() - t0, 2), "dir": direction,
                          "frame": frame}, separators=(",", ":")) + "\n")
    log.flush()


def reader():
    for line in p.stdout:
        try:
            frame = json.loads(line)
        except ValueError:
            continue
        rec("out", frame)
        inbox.put(frame)
    inbox.put(None)


threading.Thread(target=reader, daemon=True).start()


def write(msg):
    rec("in", msg)
    p.stdin.write(json.dumps(msg) + "\n")
    p.stdin.flush()


def send(method, params):
    next_id[0] += 1
    write({"jsonrpc": "2.0", "id": next_id[0], "method": method, "params": params})
    return next_id[0]


def content_for(schema):
    content = {}
    for name, prop in (schema.get("properties") or {}).items():
        is_other = name.endswith("_other")
        if scenario == "other":
            if is_other:
                content[name] = "SQLite, embedded"
        elif is_other:
            continue
        elif prop.get("oneOf"):
            content[name] = prop["oneOf"][1]["const"]
        elif prop.get("type") == "array":
            items = prop.get("items", {})
            content[name] = [o["const"] for o in items.get("anyOf", [])][:2] or items.get("enum", [])[:2]
    return content


def answer(frame):
    method = frame["method"]
    if method == "elicitation/create":
        seen["elicitation"] += 1
        result = ({"action": "decline"} if scenario == "decline" else
                  {"action": "accept",
                   "content": content_for(frame["params"].get("requestedSchema", {}))})
        write({"jsonrpc": "2.0", "id": frame["id"], "result": result})
    elif method == "session/request_permission":
        seen["permission"] += 1
        opts = frame["params"]["options"]
        pick = next((o for o in opts if o["kind"] == "allow_once"), opts[0])
        write({"jsonrpc": "2.0", "id": frame["id"],
               "result": {"outcome": {"outcome": "selected", "optionId": pick["optionId"]}}})
    else:
        seen["other"].append(method)
        write({"jsonrpc": "2.0", "id": frame["id"],
               "error": {"code": -32601, "message": "method not supported"}})


def await_response(rid, timeout=180):
    end = time.time() + timeout
    while time.time() < end:
        try:
            f = inbox.get(timeout=1)
        except queue.Empty:
            continue
        if f is None:
            return None
        if f.get("id") == rid and ("result" in f or "error" in f):
            return f
        if "method" in f and "id" in f:
            answer(f)
    return "timeout"


def step(name, rid):
    r = await_response(rid)
    print(name, "->", "timeout" if r == "timeout" else ("closed" if r is None else
          ("error " + json.dumps(r["error"]) if "error" in r else "ok")))
    return r if isinstance(r, dict) and "result" in r else None


ASK = ("Before you do anything, ask me {q}, with whatever tool or mechanism you have for asking "
       "the user a question. Wait for my answer, then reply with one line: 'GOT: ' and my answer "
       "exactly as you received it.")
PROMPTS = {"single": ASK.format(q="which database to use, Postgres or Redis (one choice)"),
           "multi": ASK.format(q="which checks to run, any of Lint, Test and Build (multi-select)"),
           "other": ASK.format(q="which database to use, Postgres or Redis (one choice)"),
           "decline": ASK.format(q="which database to use, Postgres or Redis (one choice)")}
PROMPTS["nocap"] = PROMPTS["single"]

r = step("initialize", send("initialize", {
    "protocolVersion": 1,
    "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False},
                           "terminal": False,
                           **({} if scenario == "nocap" else {"elicitation": {"form": {}}})}}))
if r:
    print("agentInfo:", r["result"].get("agentInfo"))
    s = step("session/new", send("session/new", {"cwd": workdir, "mcpServers": []}))
    if s:
        sid = s["result"]["sessionId"]
        step("prompt", send("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text":
             PROMPTS[scenario]}]}))
print("seen:", json.dumps(seen))
p.stdin.close()
try:
    p.wait(timeout=10)
except subprocess.TimeoutExpired:
    p.kill()
log.close()
