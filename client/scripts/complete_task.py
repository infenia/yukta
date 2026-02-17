import json
import sys
from http.client import HTTPConnection
from contextlib import closing

WEBSERVER_HOST = "localhost"
WEBSERVER_PORT = 8080
WEBSERVER_ENDPOINT = "/api/tasks/complete"

def http_post(host, port, location, payload):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {'Content-Type': 'application/json'}
    with closing(HTTPConnection(host, port, timeout=600)) as connection:  # Longer timeout for quality checks
        connection.request("POST", location, body=body, headers=headers)
        # Use a longer timeout for reading the response as well
        response = connection.getresponse()
        print(f"Status: {response.status}")
        print(f"Response: {response.read().decode('utf-8')}")

def main():
    session_id = "unknown"
    # Read session_id from stdin (Claude Code hook input)
    try:
        if not sys.stdin.isatty():
            data = json.load(sys.stdin)
            session_id = data.get('session_id', 'unknown')
    except Exception:
        pass

    print(f"Triggering quality checks for session: {session_id}...")
    payload = {
        "sessionId": session_id
    }
    http_post(WEBSERVER_HOST, WEBSERVER_PORT, WEBSERVER_ENDPOINT, payload)

if __name__ == "__main__":
    main()
