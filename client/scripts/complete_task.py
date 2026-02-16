import json
import sys
from http.client import HTTPConnection
from contextlib import closing

WEBSERVER_HOST = "localhost"
WEBSERVER_PORT = 8080
WEBSERVER_ENDPOINT = "/api/tasks/complete"

def http_post(host, port, location):
    headers = {'Content-Type': 'application/json'}
    payload = "{}"
    with closing(HTTPConnection(host, port, timeout=600)) as connection:  # Longer timeout for quality checks
        connection.request("POST", location, body=payload, headers=headers)
        response = connection.getresponse()
        print(f"Status: {response.status}")
        print(f"Response: {response.read().decode('utf-8')}")

def main():
    # We might still receive JSON from stdin if used in a tool hook
    try:
        # Just consume stdin if it exists, but don't strictly require it to be valid JSON
        # if we just want to trigger the checks anyway.
        if not sys.stdin.isatty():
            sys.stdin.read()
    except Exception:
        pass

    print("Triggering quality checks...")
    http_post(WEBSERVER_HOST, WEBSERVER_PORT, WEBSERVER_ENDPOINT)

if __name__ == "__main__":
    main()
