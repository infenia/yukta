#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Drive `claude -p` over stream-json and record every line in both directions.

Scenario: (A) a user message written during a turn, no interrupt: is it taken in the turn or
after it? (B) an interrupt, then a new message on the same process: does it survive?
Usage: record_claude_steer_interrupt.py OUT.jsonl WORKDIR   (OUT lines: {t, dir, line})
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


# A: a message during a turn, no interrupt: is it taken mid-turn or after the turn?
user("Run 'sleep 2; echo one' with Bash, then 'sleep 2; echo two' with Bash, then reply 'finished A'.")
o = wait(lambda o: o.get("type") == "assistant", 60)
time.sleep(1)
user("Also say the word 'banana' in your final reply.")
results = 0
while results < 2:
    r = wait(is_result, 90)
    if not isinstance(r, dict):
        print("A: no more results", r); break
    results += 1
    print("A result", results, r.get("subtype"), repr(r.get("result"))[:80])
    if results == 1:
        time.sleep(8)  # does a second result follow by itself?
        try:
            nxt = lines.get(timeout=1)
        except queue.Empty:
            nxt = "(nothing queued)"
        print("A after first result:", str(nxt)[:100])
        if nxt is not None and nxt != "(nothing queued)":
            lines.queue.appendleft(nxt)
            continue
        break
# B: interrupt, then a new message on the same process
user("Run 'sleep 5; echo x' with Bash, ten times, one call each.")
wait(lambda o: o.get("type") == "assistant", 60)
time.sleep(2)
send({"type": "control_request", "request_id": "int-2", "request": {"subtype": "interrupt"}})
r = wait(is_result, 60)
print("B interrupt result:", r if not isinstance(r, dict) else (r.get("subtype"), r.get("terminal_reason")))
time.sleep(2)
print("B alive after interrupt:", p.poll() is None)
if p.poll() is None:
    user("Reply with the single word 'alive'.")
    r = wait(is_result, 60)
    print("B next turn:", r if not isinstance(r, dict) else (r.get("subtype"), r.get("result")))
p.stdin.close()
try:
    p.wait(timeout=15)
except subprocess.TimeoutExpired:
    p.kill()
print("exit", p.returncode)
