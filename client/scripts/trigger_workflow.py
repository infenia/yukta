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
        # Longer timeout for quality checks (600s)
        with closing(HTTPConnection(host, port, timeout=600)) as connection:
            connection.request("POST", location, body=body, headers=headers)
            response = connection.getresponse()
            print(f"Status: {response.status}")
            print(f"Response: {response.read().decode('utf-8')}")
            return response.status == 200
    except Exception as e:
        print(f"Error connecting to Jagratha server: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Trigger a workflow for a Jagratha session.")
    parser.add_argument("--session-id", help="The unique session identifier")

    args, unknown = parser.parse_known_args()

    session_id = args.session_id or "unknown"

    # Attempt to read from stdin (for automated environments/hooks)
    if not args.session_id and not sys.stdin.isatty():
        try:
            data = json.load(sys.stdin)
            session_id = data.get('session_id', session_id)
        except Exception:
            pass

    print(f"Triggering quality checks for session: {session_id}...")
    payload = {
        "sessionId": session_id
    }
    http_post(WEBSERVER_HOST, WEBSERVER_PORT, WEBSERVER_ENDPOINT, payload)

if __name__ == "__main__":
    main()
