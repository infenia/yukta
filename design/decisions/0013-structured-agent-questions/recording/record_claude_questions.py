#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Drive `claude -p` over stream-json and record how AskUserQuestion is asked and answered.

One process per scenario, so each answer channel is seen in isolation. The model is told to call
AskUserQuestion with given options and then to repeat the answer it received, which is the proof
that the answer reached it (the next `user` line holds the tool_result).

Scenarios:
  single    one question, one option picked, answered by allow + updatedInput.answers
  multi     multiSelect, two options picked, joined with ", "
  other     free text instead of an option (the TUI's "Other"), same channel
  several   two questions in one call, both answered
  plain     allow with the input unchanged: what the approval-only v1 preset does today
  deny      deny with the answer in the message

Usage: record_claude_questions.py SCENARIO OUT.jsonl WORKDIR  (OUT lines: {t, dir, line})
"""
import json, queue, subprocess, sys, threading, time

scenario, out_path, workdir = sys.argv[1], sys.argv[2], sys.argv[3]
cmd = ["claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json",
       "--verbose", "--permission-prompt-tool", "stdio", "--model", "haiku", "--max-turns", "4"]
p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                     text=True, cwd=workdir, bufsize=1)
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
        if pred(o):
            return o
    return "timeout"


def reply(request_id, response):
    send({"type": "control_response", "response": {
        "subtype": "success", "request_id": request_id, "response": response}})


ASK = ("Use the AskUserQuestion tool exactly once to ask me: {q}. "
       "Then reply with one line: 'GOT: ' followed by exactly the answer the tool returned.")
PROMPTS = {
    "single": ASK.format(q="question 'Which database?', header 'Database', options 'Postgres' "
                         "(description 'relational') and 'Redis' (description 'key-value'), "
                         "single choice"),
    "multi": ASK.format(q="question 'Which checks should run?', header 'Checks', options 'Lint', "
                        "'Test' and 'Build', multiSelect true"),
    "other": ASK.format(q="question 'Which database?', header 'Database', options 'Postgres' and "
                        "'Redis', single choice"),
    "several": ASK.format(q="two questions in the same call: 'Which database?' (header 'Database', "
                          "options 'Postgres' and 'Redis') and 'Which region?' (header 'Region', "
                          "options 'EU' and 'US')"),
}
PROMPTS["plain"] = PROMPTS["single"]
PROMPTS["deny"] = PROMPTS["single"]


def answers_for(questions):
    picks = {"single": lambda q: q["options"][1]["label"],
             "multi": lambda q: ", ".join(o["label"] for o in q["options"][:2]),
             "other": lambda q: "SQLite, embedded",
             "several": lambda q: q["options"][-1]["label"]}
    return {q["question"]: picks[scenario](q) for q in questions}


send({"type": "user", "message": {"role": "user", "content": PROMPTS[scenario]},
      "parent_tool_use_id": None})
o = wait(lambda o: o.get("type") == "control_request"
         and o["request"].get("subtype") == "can_use_tool")
if isinstance(o, dict):
    req = o["request"]
    print("request:", req.get("tool_name"))
    inp = req.get("input", {})
    if req.get("tool_name") != "AskUserQuestion":
        reply(o["request_id"], {"behavior": "deny", "message": "only AskUserQuestion is allowed"})
    elif scenario == "plain":
        reply(o["request_id"], {"behavior": "allow", "updatedInput": inp})
    elif scenario == "deny":
        q = inp["questions"][0]
        reply(o["request_id"], {"behavior": "deny",
                                "message": "The user answered: " + q["options"][1]["label"]})
    else:
        reply(o["request_id"], {"behavior": "allow", "updatedInput":
                                dict(inp, answers=answers_for(inp["questions"]))})
else:
    print("no request:", o)
r = wait(lambda o: o.get("type") == "result")
print("result:", r if r in (None, "timeout") else (r.get("subtype"), r.get("result")))
p.stdin.close()
try:
    p.wait(timeout=15)
except subprocess.TimeoutExpired:
    p.kill()
print("exit", p.returncode)
log.close()
