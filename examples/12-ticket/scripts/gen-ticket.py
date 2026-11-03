#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Writes the workflows `ticket` and `step` of the ticket pipeline into session.json (design 6.1, 6.2, 15.3), the way
11-dogfood/scripts/gen-heal.py does. `yk ticket` adds them to the `dogfood` session, so they call its `tool` workflow.

  ticket  issue -> init -> route -> architect -> validate-spec -> test writer -> gate-tests -> preflight -> step -> full checks -> review -> close
  step    one step: begin (base commit, packet) -> implementer -> (DONE|BLOCKED) -> validate-step -> commit

Every node is a PROCESS_EXECUTOR running `ticketkit` (no Java); a Claude call is `ticketkit call ROLE`, which runs claude.sh --role ROLE.
Claude calls share the `claude` concurrency group; Gradle runs inside `yk check`, which queues in the `tool` group. This is the walking
skeleton (M3): one step, no retry ladder (#1882), no approvals (#1882), no GitHub intake or PR (#1880).

  gen-ticket.py          write session.json        gen-ticket.py --check   exit 1 when the committed session.json is stale (CI-style)
Edit this file, run it, commit session.json."""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
PATH = os.environ.get("TICKET_SESSION") or os.path.join(HERE, "..", "session.json")
KIT = "__TICKET__/ticketkit"
START = "${start.%s}"


def S(k):
    return START % k


def edges(src, dst, *ports):
    return [{"source": src, "target": dst, "sourcePort": p} for p in ports] if ports else [{"source": src, "target": dst}]


def pe(node_id, args, timeout=300, json_out=False, claude=False):
    env = {"YK_CLAUDE_BIN": S("claudeBin"), "TICKET_GATE_STEP": S("gateStep"), "TICKET_GATE_FINAL": S("gateFinal"), "YUKTA_DEV_STATE_DIR": S("stateDir")}
    cfg = {"command": [KIT, *args], "workingDir": S("project"), "env": env, "timeout": timeout, "timeoutSeconds": timeout,
           "failureMode": "CONTINUE", "routeByExitCode": True}
    if json_out:
        cfg["outputFormat"] = "JSON"
    node = {"nodeId": node_id, "type": "PROCESS_EXECUTOR", "config": cfg}
    if claude:
        node["config"]["engine"] = {"concurrencyGroup": "claude"}
    return node


def failed(node_id, reason):
    return {"nodeId": node_id, "type": "OUTCOME_TERMINAL", "config": {"outcome": "FAILED", "reason": reason}}


def chain(nodes, last_ok):
    """nodes: [(node, failure_reason)] in order; each success goes to the next, each failure to its own FAILED terminal."""
    out_nodes, out_edges = [], []
    for k, (n, why) in enumerate(nodes):
        out_nodes += [n, failed(n["nodeId"] + ".failed", why)]
        out_edges += edges(n["nodeId"], nodes[k + 1][0]["nodeId"] if k + 1 < len(nodes) else last_ok, "success")
        out_edges += edges(n["nodeId"], n["nodeId"] + ".failed", "failure")
    return out_nodes, out_edges


W = ["--worktree", S("worktree")]
RUN = S("run")
# ---------------------------------------------------------------- step
step_nodes = [
    (pe("begin", ["flow", "step-begin", RUN, S("step"), *W], 300), "the step base or packet failed"),
    (pe("implement", ["call", "implementer", RUN, "--step", S("step"), "--attempt", "1", *W], 2400, json_out=True, claude=True),
     "the implementer failed or its answer stayed invalid after one retry"),
]
STEP_TAIL = [(pe("validate", ["flow", "validate-step", RUN, S("step"), "--attempt", "1", *W], 3700), "the step did not pass validation (failure.json)"),
             (pe("commit", ["flow", "commit-step", RUN, S("step"), *W], 120), "the step could not be committed")]
sn, se = chain(step_nodes, "result?")
result = {"nodeId": "result?", "type": "BRANCH", "config": {"mode": "SELECT_KEY", "selector": "payload['output']['status']", "cases": {"DONE": "done"}, "defaultPort": "blocked"}}
tn, te = chain(STEP_TAIL, "ok")
STEP_WF = {"workflowId": "step", "description": "One step of a ticket: base commit and packet, the implementer, validation, commit.",
           "nodes": [{"nodeId": "trigger", "type": "CONSTANT_SOURCE", "config": {}}] + sn + [result, failed("blocked", "BLOCKED: the implementer reported it cannot do the step")] + tn
                    + [{"nodeId": "ok", "type": "CONSOLE_TERMINAL", "config": {}}],
           "edges": edges("trigger", "begin") + se + edges("result?", "validate", "done") + edges("result?", "blocked", "blocked") + te}

# ---------------------------------------------------------------- ticket
init = pe("init", ["init", S("issue"), "--run-id", S("runId"), "--root", S("project")], 300)
steps = {"nodeId": "steps", "type": "SUB_WORKFLOW", "config": {
    "workflowId": "step", "timeoutSeconds": 7200, "routeByOutcome": True,
    "payload": {k: S(k) for k in ("project", "run", "worktree", "claudeBin", "gateStep", "gateFinal", "stateDir")} | {"step": "S1"}}}
ticket_nodes = [
    (init, "init failed or the issue is skipped (no acceptance criteria, branch or worktree exists)"),
    (pe("route", ["route", RUN], 120), "route failed"),
    (pe("architect", ["call", "architect", RUN, *W], 2400, json_out=True, claude=True), "the architect failed, asked for clarification or stayed invalid"),
    (pe("validate_spec", ["validate-spec", RUN, *W], 300), "the spec is not valid (spec/validation.json)"),
    (pe("test_writer", ["call", "test_writer", RUN, *W], 2400, json_out=True, claude=True), "the test writer failed"),
    (pe("gate_tests", ["gate-tests", RUN, *W], 3700), "the tests did not pass the red check"),
    (pe("preflight", ["flow", "preflight", RUN], 60), "the walking skeleton cannot run this spec (high risk or more than one step)"),
]
tn1, te1 = chain(ticket_nodes, "steps")
after = [
    (pe("full_checks", ["flow", "full-checks", RUN, *W], 3700), "the final gate failed"),
    (pe("review", ["call", "reviewer", RUN, "--step", "S1", *W], 2400, json_out=True, claude=True), "the reviewer failed"),
]
tn2, te2 = chain(after, "verdict?")
verdict = {"nodeId": "verdict?", "type": "BRANCH", "config": {"mode": "SELECT_KEY", "selector": "payload['output']['verdict']", "cases": {"APPROVE": "approved"}, "defaultPort": "changes"}}
close = pe("close", ["flow", "close", RUN, "--events", S("events")], 60)
TICKET_WF = {"workflowId": "ticket", "description": "Turn a GitHub issue with acceptance criteria into a tested change on its own branch (walking skeleton).",
             "nodes": [{"nodeId": "trigger", "type": "CONSTANT_SOURCE", "config": {}}] + tn1 + [steps, failed("steps.failed", "a step failed (yk tree shows which)")] + tn2
                      + [verdict, failed("changes", "the reviewer requested changes"), close, {"nodeId": "done", "type": "CONSOLE_TERMINAL", "config": {"displayOutput": True}}],
             "edges": edges("trigger", "init") + te1 + edges("steps", "full_checks", "success") + edges("steps", "steps.failed", "failure") + te2
                      + edges("verdict?", "close", "approved") + edges("verdict?", "changes", "changes") + edges("close", "done")}

out = json.dumps({"workflows": {"ticket": TICKET_WF, "step": STEP_WF}}, indent=2) + "\n"
if "--check" in sys.argv:
    cur = open(PATH).read() if os.path.exists(PATH) else ""
    if cur != out:
        sys.exit("session.json is stale: run examples/12-ticket/scripts/gen-ticket.py and commit it")
    print("session.json is current")
else:
    with open(PATH, "w") as f:
        f.write(out)
    print("ticket", len(TICKET_WF["nodes"]), "nodes; step", len(STEP_WF["nodes"]), "nodes")
