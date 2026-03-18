#
# Copyright 2026 Infenia Private Limited
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
import json
import os
import sys
import argparse
from http.client import HTTPConnection
from contextlib import closing

# Configuration variables - easy for users to modify
# Available processor types:
# - "PROCESS_EXECUTOR": Execute external commands/scripts (Linux/macOS/Windows compatible)
# - "MAPPER": Transform data using Handlebars templates
# Available trigger types:
# - "api-trigger": REST API trigger
# Available terminal types:
# - "console": Console output terminal
DEFAULT_WORKFLOW_NODES = [
    {
        "nodeId": "trigger-1",
        "type": "api-trigger",
        "config": {}
    },
    {
        "nodeId": "processor-1",
        "type": "PROCESS_EXECUTOR",
        "config": {
            "command": ["echo", "Quality check execution"],
            "streamOutput": True
        }
    },
    {
        "nodeId": "terminal-1",
        "type": "console",
        "config": {}
    }
]
DEFAULT_WORKFLOW_EDGES = [
    {"source": "trigger-1", "target": "processor-1"},
    {"source": "processor-1", "target": "terminal-1"}
]

WEBSERVER_HOST = os.environ.get("YUKTA_HOST", "localhost")
WEBSERVER_PORT = int(os.environ.get("YUKTA_PORT", 8080))
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
        print(f"Error connecting to Yukta server: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Initialize a Yukta session.")
    parser.add_argument("--session-id", help="The unique session identifier")
    parser.add_argument("--description", help="A human-readable description of the session (mandatory)")
    parser.add_argument("--project-path", help="The root path of the project")
    parser.add_argument("--initiator", help="The initiator name (mandatory)")
    parser.add_argument("--tags", help="Additional tags as key=value pairs, comma separated (e.g., clientId=c1,env=prod)")

    args, unknown = parser.parse_known_args()

    session_id = args.session_id or "sim-session-1"
    description = args.description
    project_root = args.project_path or os.getcwd()
    initiator = args.initiator
    tags = {}
    if args.tags:
        for pair in args.tags.split(','):
            if '=' in pair:
                k, v = pair.split('=', 1)
                tags[k.strip()] = v.strip()

    # Attempt to read from stdin (for automated environments/hooks)
    if not args.session_id and not sys.stdin.isatty():
        try:
            data = json.load(sys.stdin)
            session_id = data.get('session_id', session_id)
            description = data.get('description') or description or data.get('source') or "Yukta Session"
            project_root = data.get('project_root') or data.get('cwd') or project_root
            initiator = data.get('initiator', initiator)
            tags.update(data.get('tags', {}))
        except Exception:
            pass

    if not description:
        print("Error: --description is mandatory.")
        sys.exit(1)

    if not initiator:
        print("Error: --initiator is mandatory.")
        sys.exit(1)

    print(f"Initializing for session: {session_id} by {initiator} at {project_root}")

    # Construct the ConfigRequest payload
    # Add projectPath to nodes that support it
    nodes = []
    for node in DEFAULT_WORKFLOW_NODES:
        new_node = node.copy()
        node_config = new_node.get("config", {}).copy()

        # Add projectPath for processors that support it (deep copy to avoid mutation)
        if new_node["type"] in ["PROCESS_EXECUTOR", "SCRIPTING", "mapper"]:
            node_config["projectPath"] = project_root

        new_node["config"] = node_config
        nodes.append(new_node)

    payload = {
        "sessionId": session_id,
        "description": description,
        "initiator": initiator,
        "tags": tags,
        "projectPath": project_root,
        "workflows": {
            "quality-check": {
                "description": "Standard quality gate for checking project status",
                "nodes": nodes,
                "edges": DEFAULT_WORKFLOW_EDGES
            }
        }
    }

    print("Sending configuration to Yukta server...")
    if not http_post(WEBSERVER_HOST, WEBSERVER_PORT, WEBSERVER_ENDPOINT, payload):
        sys.exit(1)

if __name__ == "__main__":
    main()
