import json
import sys
from http.client import HTTPConnection
from contextlib import closing

WEBSERVER_HOST = "localhost"
WEBSERVER_PORT = 8080
WEBSERVER_ENDPOINT = "/api/files"

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

def extract_file_path(tool_name, tool_input):
    # Support for various AI agent tool schemas
    if tool_name in ["Write", "Edit", "MultiEdit"]:
        return tool_input.get('file_path', 'unknown')
    if tool_name == "NotebookEdit":
        return tool_input.get('notebook_path', 'unknown')

    # Support for Claude Code tool names
    if tool_name in ["write_to_file", "replace_in_file", "insert_content_at_line", "apply_diff"]:
        return tool_input.get('relative_path', 'unknown')

    return 'unknown'

def main():
    try:
        data = json.load(sys.stdin)
    except json.JSONDecodeError:
        print("Error: Invalid JSON input")
        sys.exit(1)

    session_id = data.get('session_id', 'unknown')
    tool_name = data.get('tool_name', 'unknown')

    # Expanded list of modification tools including Claude Code tools
    modification_tools = [
        "Write", "Edit", "MultiEdit", "NotebookEdit",
        "write_to_file", "replace_in_file", "insert_content_at_line", "apply_diff"
    ]

    if tool_name in modification_tools:
        tool_input = data.get('tool_input', {})
        file_path = extract_file_path(tool_name, tool_input)

        if file_path != 'unknown':
            payload = {
                "path": file_path,
                "sessionId": session_id,
            }
            # Extract content if available (for 'write_to_file' or 'replacement')
            content = tool_input.get('replacement') or tool_input.get('content') or tool_input.get('diff')
            if content:
                payload["content"] = content

            http_post(WEBSERVER_HOST, WEBSERVER_PORT, WEBSERVER_ENDPOINT, payload)
        else:
            print("Error: Could not extract file path from input")
    else:
        print(f"Skipping: tool '{tool_name}' is not a modification tool")

if __name__ == "__main__":
    main()
