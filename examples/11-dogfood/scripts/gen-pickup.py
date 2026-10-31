#!/usr/bin/env python3
"""Writes the `pickup` workflow into session.json: a SCHEDULE_TRIGGER that ticks every INTERVAL_MS and runs `pickup.sh tick`, which hands
the next assigned, Ready issue to the yukta-pickup channel (channel/yukta-pickup.py). One execution lives as long as the server (yk pickup on
starts it, yk pickup off stops it, a server swap restarts it). A failing tick is reported and the schedule goes on.
Edit INTERVAL_MS here, run this script, commit session.json."""
import json, os

INTERVAL_MS = 30 * 60 * 1000

nodes = [
    {"nodeId": "every", "type": "SCHEDULE_TRIGGER", "config": {
        "intervalMs": INTERVAL_MS, "timeoutSeconds": INTERVAL_MS // 1000 + 600}},
    {"nodeId": "tick", "type": "PROCESS_EXECUTOR", "config": {
        "command": ["bash", "__DOGFOOD__/scripts/pickup.sh", "tick"], "workingDir": "__PROJECT_PATH__",
        "timeout": 120, "failureMode": "CONTINUE", "routeByExitCode": True}},
    {"nodeId": "ok", "type": "CONSOLE_TERMINAL", "config": {"displayOutput": True}},
    {"nodeId": "failed", "type": "CONSOLE_TERMINAL", "config": {"displayOutput": True}},
]
edges = [
    {"source": "every", "target": "tick"},
    {"source": "tick", "target": "ok", "sourcePort": "success"},
    {"source": "tick", "target": "failed", "sourcePort": "failure"},
]

path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "session.json")
with open(path) as f:
    d = json.load(f)
d["workflows"]["pickup"] = {"workflowId": "pickup", "description":
    "Scheduled pickup: every 30 minutes hand the next assigned, Ready issue to the yukta-pickup channel (yk pickup on)",
    "nodes": nodes, "edges": edges}
with open(path, "w") as f:
    json.dump(d, f, indent=2)
    f.write("\n")
print("pickup", len(nodes), "nodes")
