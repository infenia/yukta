import json
import os
import sys
import argparse
from http.client import HTTPConnection
from contextlib import closing

# Configuration variables - easy for users to modify
DEFAULT_PLUGIN_TYPE = "gradle"
DEFAULT_WORKFLOW_NODES = [
    {
        "nodeId": "trigger-1",
        "type": DEFAULT_PLUGIN_TYPE,
        "config": {
            "tasks": ["check"]
        }
    },
    {
        "nodeId": "terminal-1",
        "type": "console",
        "config": {}
    }
]
DEFAULT_WORKFLOW_EDGES = [
    {
        "source": "trigger-1",
        "target": "terminal-1"
    }
]

WEBSERVER_HOST = os.environ.get("JAGRATHA_HOST", "localhost")
WEBSERVER_PORT = int(os.environ.get("JAGRATHA_PORT", 8080))
WEBSERVER_ENDPOINT = "/api/config"

def http_post(host, port, location, payload):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {'Content-Type': 'application/json'}
    try:
        with closing(HTTPConnection(host, port, timeout=10)) as connection:
            connection.request("POST", location, body=body, headers=headers)
            response = connection.getresponse()
            print(f"HTTP Status: {response.status}")
            response_body = response.read().decode('utf-8')
            try:
                data = json.loads(response_body)
                if "message" in data:
                    print(f"Message: {data['message']}")
                if "error" in data:
                    print(f"Error: {data['error']}")
                if "errors" in data and data["errors"]:
                    print("Field Errors:")
                    for err in data["errors"]:
                        print(f"  - {err['field']}: {err['message']}")
            except json.JSONDecodeError:
                print(f"Response: {response_body}")
            return response.status == 200
    except Exception as e:
        print(f"Error connecting to Jagratha server: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Initialize a Jagratha session.")
    parser.add_argument("--session-id", help="The unique session identifier")
    parser.add_argument("--project-path", help="The root path of the project")

    args, unknown = parser.parse_known_args()

    session_id = args.session_id or "unknown"
    project_root = args.project_path or os.getcwd()

    # Attempt to read from stdin (for automated environments/hooks)
    if not args.session_id and not sys.stdin.isatty():
        try:
            data = json.load(sys.stdin)
            session_id = data.get('session_id', session_id)
            project_root = data.get('project_root', project_root)
        except Exception:
            pass

    print(f"Initializing for session: {session_id} at {project_root}")

    # Construct the ConfigRequest payload
    # Ensure projectRoot is in the trigger node config as required by GradlePlugin
    nodes = []
    for node in DEFAULT_WORKFLOW_NODES:
        new_node = node.copy()
        if new_node["type"] == "gradle":
            node_config = new_node.get("config", {}).copy()
            node_config["projectRoot"] = project_root
            new_node["config"] = node_config
        nodes.append(new_node)

    payload = {
        "sessionId": session_id,
        "projectPath": project_root,
        "workflow": {
            "nodes": nodes,
            "edges": DEFAULT_WORKFLOW_EDGES
        }
    }

    print("Sending configuration to Jagratha server...")
    http_post(WEBSERVER_HOST, WEBSERVER_PORT, WEBSERVER_ENDPOINT, payload)

if __name__ == "__main__":
    main()
