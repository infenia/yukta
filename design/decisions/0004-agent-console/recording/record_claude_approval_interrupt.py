#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Drive `claude -p` over stream-json and record every line in both directions.

Scenario: (1) a prompt that needs a tool, answer the permission request (can_use_tool);
(2) a long second turn with a user message written during it, then an interrupt.
Usage: record_claude_approval_interrupt.py OUT.jsonl WORKDIR   (OUT lines: {t, dir, line})
"""
import json, subprocess, sys, threading, time, queue

out_path, workdir = sys.argv[1], sys.argv[2]
cmd = ["claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json",
       "--verbose", "--permission-prompt-tool", "stdio", "--model", "haiku",
       "--max-turns", "6"]
p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, text=True, cwd=workdir, bufsize=1)
log = open(out_path, "w")
t0 = time.time()
lines = queue.Queue()
lock = threading.Lock()

def rec(direction, line):
    with lock:
        log.write(json.dumps({"t": round(time.time() - t0, 2), "dir": direction,
                              "line": line}) + "\n")
        log.flush()

def reader():
    for l in p.stdout:
        l = l.rstrip("\n")
        rec("out", l)
        lines.put(l)
    lines.put(None)

def err():
    for l in p.stderr:
        rec("err", l.rstrip("\n"))

threading.Thread(target=reader, daemon=True).start()
threading.Thread(target=err, daemon=True).start()

def send(obj):
    s = json.dumps(obj)
    rec("in", s)
    p.stdin.write(s + "\n")
    p.stdin.flush()

def user(text):
    send({"type": "user", "message": {"role": "user", "content": text}})

def wait(pred, timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        try:
            l = lines.get(timeout=1)
        except queue.Empty:
            continue
        if l is None:
            return None
        try:
            o = json.loads(l)
        except ValueError:
            continue
        if o.get("type") == "control_request" and o["request"].get("subtype") == "can_use_tool" \
                and not pred(o):
            # answer stray permission requests as allow so the run proceeds
            allow(o)
            continue
        if pred(o):
            return o
    return "timeout"

def allow(o):
    inp = o["request"].get("input", {})
    send({"type": "control_response", "response": {
        "subtype": "success", "request_id": o["request_id"],
        "response": {"behavior": "allow", "updatedInput": inp}}})

def is_perm(o):
    return o.get("type") == "control_request" and o["request"].get("subtype") == "can_use_tool"

def is_result(o):
    return o.get("type") == "result"

# turn 1: a tool that needs approval (write a file), answer the request
user("Create a file named hello.txt in the current directory containing the word hi. "
     "Use the Write tool. Then reply 'done'.")
o = wait(is_perm)
print("perm:", o if o in (None, "timeout") else o["request"].get("tool_name"))
if isinstance(o, dict):
    allow(o)
r = wait(is_result)
print("turn1 result:", r if r in (None, "timeout") else r.get("subtype"))

# turn 2: a long turn; write a message during it, then interrupt
user("Count slowly from 1 to 40, one number per line, using a Bash call "
     "'sleep 1; echo N' for every number, one call per number.")
o = wait(lambda o: o.get("type") == "assistant" or is_perm(o), 60)
print("turn2 first:", o if o in (None, "timeout") else o.get("type"))
if isinstance(o, dict) and is_perm(o):
    allow(o)
time.sleep(3)
user("Also tell me the word 'banana' at the end.")   # message during the turn
time.sleep(6)
send({"type": "control_request", "request_id": "int-1",
      "request": {"subtype": "interrupt"}})
r = wait(is_result, 60)
print("after interrupt:", r if r in (None, "timeout") else (r.get("subtype"), r.get("is_error")))
# what happens to the queued message? wait a little for further output
t = time.time()
while time.time() - t < 25:
    try:
        l = lines.get(timeout=2)
    except queue.Empty:
        continue
    if l is None:
        break
p.stdin.close()
try:
    p.wait(timeout=15)
except subprocess.TimeoutExpired:
    p.kill()
print("exit", p.returncode)
log.close()
