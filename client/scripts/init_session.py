import json
import sys
from http.client import HTTPConnection
from contextlib import closing

WEBSERVER_HOST = "localhost"
WEBSERVER_PORT = 8080
WEBSERVER_ENDPOINT = "/api/config"

def http_post(host, port, location, payload):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {'Content-Type': 'application/json'}
    with closing(HTTPConnection(host, port, timeout=10)) as connection:
        connection.request("POST", location, body=body, headers=headers)
        response = connection.getresponse()
        print(f"Status: {response.status}")
        print(f"Response: {response.read().decode('utf-8')}")

def main():
    session_id = "unknown"
    # Attempt to read session_id from stdin (Claude Code hook input)
    try:
        if not sys.stdin.isatty():
            data = json.load(sys.stdin)
            session_id = data.get('session_id', 'unknown')
            print(f"Initializing for session: {session_id}")
    except Exception:
        pass

    # Hardcoded configuration as per current implementation requirements
    # These values match the default configuration in config.yaml
    payload = {
        "sessionId": session_id,
        "projectPath": "/tmp/external-project",
        "gradlePath": "./gradlew",
        "tasks": [
            "spotlessApply",
            "spotlessCheck",
            "checkstyleMain",
            "test"
        ],
        "executionTimeout": 600,
        "fileLogDir": "/tmp/jagratha/logs/files",
        "resultLogDir": "/tmp/jagratha/logs/results"
    }

    print("Sending configuration to Jagratha server...")
    http_post(WEBSERVER_HOST, WEBSERVER_PORT, WEBSERVER_ENDPOINT, payload)

if __name__ == "__main__":
    main()
