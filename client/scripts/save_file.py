import json
import os
import sys
import argparse
from http.client import HTTPConnection
from contextlib import closing

WEBSERVER_HOST = os.environ.get("JAGRATHA_HOST", "localhost")
WEBSERVER_PORT = int(os.environ.get("JAGRATHA_PORT", 8080))
WEBSERVER_ENDPOINT = "/api/workflow/trigger"

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
    parser = argparse.ArgumentParser(description="Log a file path for a Jagratha session.")
    parser.add_argument("--session-id", help="The unique session identifier")
    parser.add_argument("--path", help="The relative path of the file")
    parser.add_argument("--status", default="PENDING", help="The status of the file")
    parser.add_argument("--workflow-id", default="file-update", help="The workflow identifier")

    args, unknown = parser.parse_known_args()

    session_id = args.session_id or "unknown"
    file_path = args.path or "unknown"
    status = args.status
    workflow_id = args.workflow_id

    # Attempt to read from stdin (for automated environments/hooks)
    if (not args.session_id or not args.path) and not sys.stdin.isatty():
        try:
            data = json.load(sys.stdin)
            session_id = data.get('session_id', session_id)
            # Support common tool input formats if needed, but keeping it simple
            if not args.path:
                file_path = (
                    data.get('path') or
                    data.get('relative_path') or
                    data.get('tool_input', {}).get('file_path') or
                    file_path
                )

                # If we have cwd and file_path is absolute, make it relative
                cwd = data.get('cwd')
                if cwd and os.path.isabs(file_path):
                    try:
                        file_path = os.path.relpath(file_path, cwd)
                    except ValueError:
                        pass
        except Exception:
            pass

    if file_path == "unknown":
        print("Error: File path is required.")
        sys.exit(1)

    payload = {
        "sessionId": session_id,
        "workflowId": workflow_id,
        "payload": {
            "path": file_path,
            "status": status
        }
    }

    print(f"Triggering workflow '{workflow_id}' for file '{file_path}' with status '{status}' (Session: '{session_id}')...")
    if not http_post(WEBSERVER_HOST, WEBSERVER_PORT, WEBSERVER_ENDPOINT, payload):
        sys.exit(1)

if __name__ == "__main__":
    main()
