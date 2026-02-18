import json
import os
import sys
from http.client import HTTPConnection
from contextlib import closing

WEBSERVER_HOST = "localhost"
WEBSERVER_PORT = 8080
WEBSERVER_ENDPOINT = "/api/config"

def http_post(host, port, location, payload):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {'Content-Type': 'application/json'}
    try:
        with closing(HTTPConnection(host, port, timeout=10)) as connection:
            connection.request("POST", location, body=body, headers=headers)
            response = connection.getresponse()
            print(f"Status: {response.status}")
            print(f"Response: {response.read().decode('utf-8')}")
    except Exception as e:
        print(f"Error connecting to Jagratha server: {e}")

def main():
    session_id = "unknown"
    project_root = os.getcwd()

    # Attempt to read session_id and project_root from stdin (Claude Code hook input)
    try:
        if not sys.stdin.isatty():
            data = json.load(sys.stdin)
            session_id = data.get('session_id', 'unknown')
            project_root = data.get('project_root', project_root)
            print(f"Initializing for session: {session_id} at {project_root}")
    except Exception:
        pass

    # Hardcoded configuration as per current implementation requirements
    # 'workflows' must be non-empty to pass validation
    payload = {
        "sessionId": session_id,
        "projectPath": project_root,
        "pluginName": "gradle",
        "pluginConfig": {
            "gradlePath": "./gradlew"
        },
        "tasks": [
            "spotlessApply",
            "spotlessCheck",
            "checkstyleMain",
            "test"
        ],
        "workflows": [
            {
                "task": "spotlessApply"
            },
            {
                "task": "spotlessCheck"
            },
            {
                "task": "checkstyleMain",
                "processor": {
                    "name": "checkstyle-xml-to-jsonl",
                    "config": {}
                }
            },
            {
                "task": "test"
            }
        ],
        "executionTimeout": 600
    }

    print("Sending configuration to Jagratha server...")
    http_post(WEBSERVER_HOST, WEBSERVER_PORT, WEBSERVER_ENDPOINT, payload)

if __name__ == "__main__":
    main()
