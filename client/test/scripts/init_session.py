import json
import os
from http.client import HTTPConnection
from contextlib import closing

# --- MANUAL CONFIGURATION ---
CONFIG = {
    "host": os.environ.get("JAGRATHA_HOST", "localhost"),
    "port": int(os.environ.get("JAGRATHA_PORT", 8080)),
    "endpoint": "/api/config",
    "payload": {
        "sessionId": "claude-session-01",
        "description": "Claude Code Quality Gate",
        "initiator": "Claude-AI",
        "projectPath": "/media/arun/Infenia/Infenia/Development/Public/jagratha",
        "workflows": {
            "quality-check": {
                "description": "Standard quality gate for checking project status",
                "nodes": [
                    {
                        "nodeId": "gradle-check",
                        "type": "gradle",
                        "config": {
                            "tasks": ["spotlessApply"],
                            "projectRoot": "/media/arun/Infenia/Infenia/Development/Public/jagratha"
                        }
                    },
                    {"nodeId": "terminal-1", "type": "console", "config": {}}
                ],
                "edges": [{"source": "gradle-check", "target": "terminal-1"}]
            }
        }
    }
}

def send_config():
    host, port = CONFIG["host"], CONFIG["port"]
    body = json.dumps(CONFIG["payload"]).encode("utf-8")

    print(f"Connecting to Jagratha at {host}:{port}...")

    try:
        with closing(HTTPConnection(host, port, timeout=5)) as conn:
            conn.request("POST", CONFIG["endpoint"], body=body, headers={'Content-Type': 'application/json'})
            resp = conn.getresponse()
            print(f"Status: {resp.status} - {resp.read().decode()}")
            return resp.status == 200
    except Exception as e:
        print(f"Connection Failed: {e}")
        return False

if __name__ == "__main__":
    if not send_config():
        exit(1)