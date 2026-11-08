#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""A minimal supervisor in Python, against /api/agent/v1, with the standard library only.

    YUKTA_URL=http://localhost:9858 YUKTA_AGENT_TOKEN=yag_... python3 agent.py

It follows the signals of its supervisor, pauses a run when a node of it fails, and has no opinion
about reviews. The supervisor decides whether each command is allowed.
"""
import json
import os
import time
import urllib.request

URL = os.environ.get("YUKTA_URL", "http://localhost:9858").rstrip("/")
TOKEN = os.environ["YUKTA_AGENT_TOKEN"]


def call(method, path, body=None):
    request = urllib.request.Request(
        URL + "/api/agent/v1" + path,
        data=None if body is None else json.dumps(body).encode(),
        method=method,
        headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def decide(envelope):
    """Returns what to send for one signal: (path, body) or None."""
    signal = envelope["signal"]
    kind = envelope["kind"]
    if kind == "REVIEW":
        return "/reviews/" + signal["id"], {"verdict": "NO_OPINION"}
    event = signal.get("event") or {}
    if kind == "EVENT" and event.get("status") == "FAILURE" and event.get("nodeId"):
        run = event["executionId"]
        return "/directives", {
            "key": "pause-" + run + "-" + str(envelope["cursor"]),
            "cause": "event:" + signal["id"],
            "reason": "node " + event["nodeId"] + " failed",
            "command": {"type": "PauseWorkflow", "executionId": run},
        }
    return None


def stream(after):
    request = urllib.request.Request(
        URL + "/api/agent/v1/events?after=" + str(after),
        headers={"Authorization": "Bearer " + TOKEN, "Accept": "text/event-stream"},
    )
    with urllib.request.urlopen(request) as response:
        for raw in response:
            line = raw.decode().strip()
            if line.startswith("data:"):
                yield json.loads(line[5:])


def main():
    cursor = call("GET", "/snapshot")["data"]["cursor"]
    while True:
        try:
            for envelope in stream(cursor):
                cursor = envelope["cursor"]
                answer = decide(envelope)
                if answer:
                    call("POST", *answer)
        except OSError as error:  # the stream ended or the server is away: resume after the cursor
            print("reconnecting:", error)
            time.sleep(2)


if __name__ == "__main__":
    main()
