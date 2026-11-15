#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Record an Agent Client Protocol session with an agent started as `<command...>`.

Flow: initialize, session/new, session/prompt (asks for a file write so the agent requests
permission), a session/cancel during a second prompt. Every line in both directions is written to
OUT.jsonl as {"t": seconds, "dir": "in"|"out", "frame": {...}}. A failed step is recorded as it
happened and the script stops; nothing is invented.

Usage: record_acp.py OUT.jsonl WORKDIR -- gemini --acp
"""
import json, os, queue, subprocess, sys, threading, time

out_path, workdir = sys.argv[1], os.path.abspath(sys.argv[2])
cmd = sys.argv[sys.argv.index("--") + 1:]
p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                     text=True, cwd=workdir, bufsize=1)
log = open(out_path, "w")
t0 = time.time()
inbox = queue.Queue()
next_id = [0]


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


def send(method, params, notification=False):
    msg = {"jsonrpc": "2.0", "method": method, "params": params}
    if not notification:
        next_id[0] += 1
        msg["id"] = next_id[0]
    rec("in", msg)
    p.stdin.write(json.dumps(msg) + "\n")
    p.stdin.flush()
    return msg.get("id")


def answer_requests(frame):
    """Answer agent requests: allow a permission request, refuse anything else."""
    if frame.get("method") == "session/request_permission":
        opts = frame["params"]["options"]
        pick = next((o for o in opts if o["kind"] == "allow_once"), opts[0])
        reply = {"jsonrpc": "2.0", "id": frame["id"],
                 "result": {"outcome": {"outcome": "selected", "optionId": pick["optionId"]}}}
    else:
        reply = {"jsonrpc": "2.0", "id": frame["id"],
                 "error": {"code": -32601, "message": "method not supported"}}
    rec("in", reply)
    p.stdin.write(json.dumps(reply) + "\n")
    p.stdin.flush()


def await_response(rid, timeout=120, on_update=None):
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
            answer_requests(f)
        elif on_update:
            on_update(f)
    return "timeout"


def step(name, rid, **kw):
    r = await_response(rid, **kw)
    print(name, "->", "timeout" if r == "timeout" else ("closed" if r is None else
          ("error " + json.dumps(r["error"]) if "error" in r else "ok")))
    return r if isinstance(r, dict) and "result" in r else None


r = step("initialize", send("initialize", {
    "protocolVersion": 1,
    "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False}}))
if r:
    methods = [m["id"] for m in r["result"].get("authMethods", [])]
    print("authMethods:", methods)
    s = step("session/new", send("session/new", {"cwd": workdir, "mcpServers": []}))
    if s:
        sid = s["result"]["sessionId"]
        step("prompt 1", send("session/prompt", {"sessionId": sid, "prompt": [
            {"type": "text", "text": "Create a file named hello.txt containing hi, then say done."}]}))
        pid = send("session/prompt", {"sessionId": sid, "prompt": [
            {"type": "text", "text": "Count slowly from 1 to 50, one number per line."}]})
        time.sleep(4)
        send("session/cancel", {"sessionId": sid}, notification=True)
        step("prompt 2 after cancel", pid)
p.stdin.close()
try:
    p.wait(timeout=10)
except subprocess.TimeoutExpired:
    p.kill()
