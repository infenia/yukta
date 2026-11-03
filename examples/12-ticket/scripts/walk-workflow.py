#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""walk-workflow.py SESSION WORKFLOW PAYLOAD.json: runs a generated workflow without a server (a test stand-in for the engine).

Supports what gen-ticket.py emits: CONSTANT_SOURCE, PROCESS_EXECUTOR (exit code routing, JSON output, ${start.x} in command and env),
BRANCH (SELECT_KEY on payload['output'][key]), SUB_WORKFLOW (payload mapping), OUTCOME_TERMINAL, CONSOLE_TERMINAL. Prints one
`NODE <id> <port>` line per node run and `OUTCOME <SUCCESS|FAILED> <reason>`; exit 0 on SUCCESS. __TICKET__ is the folder of ticketkit."""
import json, os, pathlib, re, subprocess, sys

KIT = str(pathlib.Path(__file__).resolve().parent.parent)


def sub(v, start):
    if isinstance(v, str):
        return re.sub(r"\$\{start\.(\w+)\}", lambda m: str(start[m.group(1)]), v.replace("__TICKET__", KIT))
    if isinstance(v, list):
        return [sub(x, start) for x in v]
    return {k: sub(x, start) for k, x in v.items()} if isinstance(v, dict) else v


def walk(wfs, name, start):
    wf = wfs[name]
    nodes = {n["nodeId"]: n for n in wf["nodes"]}
    out, cur, port = None, "trigger", None
    while True:
        n, c, t = nodes[cur], nodes[cur]["config"], nodes[cur]["type"]
        if t == "OUTCOME_TERMINAL":
            print(f"NODE {cur} -"); return c["outcome"], c["reason"]
        if t == "CONSOLE_TERMINAL":
            print(f"NODE {cur} -"); return "SUCCESS", ""
        if t == "PROCESS_EXECUTOR":
            env = dict(os.environ, **sub(c["env"], start))
            r = subprocess.run(sub(c["command"], start), capture_output=True, text=True, env=env, cwd=start["project"])
            try:
                out = json.loads(r.stdout) if c.get("outputFormat") == "JSON" else None
            except ValueError:
                out = None
            port = "success" if r.returncode == 0 else "failure"
            if r.returncode:
                print((r.stdout + r.stderr)[-600:], file=sys.stderr)
        elif t == "BRANCH":
            key = re.search(r"\['output'\]\['(\w+)'\]", c["selector"]).group(1)
            port = c["cases"].get(str((out or {}).get(key)), c["defaultPort"])
        elif t == "SUB_WORKFLOW":
            outcome, why = walk(wfs, c["workflowId"], sub(c["payload"], start))
            port = "success" if outcome == "SUCCESS" else "failure"
        else:
            port = None
        print(f"NODE {cur} {port or '-'}")
        nxt = [e["target"] for e in wf["edges"] if e["source"] == cur and e.get("sourcePort") in (None, port)]
        if not nxt:
            return "FAILED", f"{cur} ended with no edge for {port}"
        cur = nxt[0]


if __name__ == "__main__":
    wfs = json.load(open(sys.argv[1]))["workflows"]
    outcome, why = walk(wfs, sys.argv[2], json.load(open(sys.argv[3])))
    print(f"OUTCOME {outcome} {why}")
    sys.exit(0 if outcome == "SUCCESS" else 1)
