#!/usr/bin/env python3
"""Writes the `check` workflow into session.json: one SUB_WORKFLOW call of `tool` per stage, in this order (cheap first).
`yk check` puts each stage's command in the start payload (cmd.<stage>, from scripts/plan.sh); a stage without a command passes
at once.

A failed stage goes to the `heal` workflow (scripts/gen-heal.py): its free automatic fix, then the System One triage and the model
ladder. A healed stage goes on with the next one; a failed heal ends the run with outcome FAILED, reason = the stage
(failed_node). `yk check --no-heal` sends heal = "off": heal ends at once, so the stage fails as it would without it. licenses and
security are never healed. After the last stage, when anything was healed, `verify` runs the Gradle tasks of the stages again.
Edit STAGES or NO_HEAL here, run this script, commit session.json."""
import json, os

STAGES = [
    "reuse", "actionlint", "docs", "spotless", "cli_fmt_check", "cli_mod_verify", "ui_prettier",
    "compile", "cli_vet", "ui_tsc", "ui_eslint", "ui_headers", "style", "spotbugs", "cli_lint",
    "cli_sec", "tests", "ui_vitest", "coverage", "cli_cover_check", "cli_vuln", "licenses",
    "security", "ui_build",
]
NO_HEAL = {"licenses", "security"}  # a model may not fix these

PASS = ["project", "healDir", "modules", "events", "models", "timeout", "rules", "claudeBin",
        "triage", "triageModel", "triageUrl", "heal", "budgetCheck"]


def tool(stage, node_id, command=None):
    return {"nodeId": node_id, "type": "SUB_WORKFLOW", "config": {
        "workflowId": "tool", "timeoutSeconds": 3720, "routeByOutcome": True,
        "payload": {"project": "${start.project}", "command": command or "${start.cmd.%s}" % stage,
                    "timeout": "${start.timeout}", "label": stage}}}


def failed(stage):
    return {"nodeId": stage + ".failed", "type": "OUTCOME_TERMINAL", "config": {"outcome": "FAILED", "reason": stage}}


nodes = [{"nodeId": "trigger", "type": "CONSTANT_SOURCE", "config": {}}]
edges = []
exits = [("trigger", None)]  # what the next stage hangs on: (node, port)


def edge(src, dst, port=None):
    edges.append({"source": src, "target": dst, **({"sourcePort": port} if port else {})})


for s in STAGES:
    nodes.append(tool(s, s))
    for src, port in exits:
        edge(src, s, port)
    nodes.append(failed(s))
    if s in NO_HEAL:
        edge(s, s + ".failed", "failure")
        exits = [(s, "success")]
        continue
    # a payload value "${payload}" reaches a sub-workflow as text, not as the map (gap G11): the failed stage's output goes as a string
    nodes.append({"nodeId": s + ".fail", "type": "MAPPER", "config": {"mode": "PROJECTION", "mapping": {
        "stdout": "payload['results'] == null ? '' : (payload['results'].size() > 0 ? payload['results'][0]['stdout'] : '')"}}})
    nodes.append({"nodeId": s + ".heal", "type": "SUB_WORKFLOW", "config": {
        "workflowId": "heal", "timeoutSeconds": 21600, "routeByOutcome": True,
        "payload": {**{k: "${start.%s}" % k for k in PASS}, "stdout": "${payload.stdout}", "stage": s, "command": "${start.cmd.%s}" % s,
                    "format": "${start.fmt.%s}" % s, "auto": "${start.auto.%s}" % s}}})
    edge(s, s + ".fail", "failure")
    edge(s + ".fail", s + ".heal")
    edge(s + ".heal", s + ".failed", "failure")
    exits = [(s, "success"), (s + ".heal", "success")]

# verify: only when something was healed, the Gradle tasks of every stage once more (a fix may break a stage that ran before it)
nodes.append({"nodeId": "healed?", "type": "PROCESS_EXECUTOR", "config": {
    "command": ["__DOGFOOD__/scripts/yukta-heal.sh", "healed"], "workingDir": "${start.project}", "env": {"YUKTA_HEAL_DIR": "${start.healDir}", "YUKTA_STAGE": "-"},
    "timeout": 60, "timeoutSeconds": 60, "failureMode": "CONTINUE", "routeByExitCode": True}})
for src, port in exits:
    edge(src, "healed?", port)
nodes.append(tool("verify", "verify", "${start.verifyCommand}"))
nodes.append(failed("verify"))
# a failed verify goes back to heal (stage "verify", the same Gradle tasks as its command), like any stage
nodes.append({"nodeId": "verify.fail", "type": "MAPPER", "config": {"mode": "PROJECTION", "mapping": {
    "stdout": "payload['results'] == null ? '' : (payload['results'].size() > 0 ? payload['results'][0]['stdout'] : '')"}}})
nodes.append({"nodeId": "verify.heal", "type": "SUB_WORKFLOW", "config": {
    "workflowId": "heal", "timeoutSeconds": 21600, "routeByOutcome": True,
    "payload": {**{k: "${start.%s}" % k for k in PASS}, "stdout": "${payload.stdout}", "stage": "verify", "command": "${start.verifyCommand}",
                "format": "${start.fmt.spotless}", "auto": "-"}}})
nodes.append({"nodeId": "done", "type": "CONSOLE_TERMINAL", "config": {"displayOutput": True}})
edge("healed?", "verify", "success")
edge("healed?", "done", "failure")
edge("verify", "done", "success")
edge("verify", "verify.fail", "failure")
edge("verify.fail", "verify.heal")
edge("verify.heal", "done", "success")
edge("verify.heal", "verify.failed", "failure")

path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "session.json")
d = json.load(open(path))
d["workflows"].pop("check-plain", None)
d["workflows"]["check"] = {"workflowId": "check", "description":
    "The scoped, self-healing quality gate: one tool call per planned stage, a failed stage goes to heal (yk check)",
    "nodes": nodes, "edges": edges}
print("check", len(nodes), "nodes")
with open(path, "w") as f:
    json.dump(d, f, indent=2)
    f.write("\n")
