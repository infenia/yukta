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
import http.client

# Jagratha Server Configuration
WEBSERVER_HOST = os.environ.get("JAGRATHA_HOST", "localhost")
WEBSERVER_PORT = int(os.environ.get("JAGRATHA_PORT", 8080))

# Workflow Configuration - Set these values as needed
WORKFLOW_ID = "quality-check"
PAYLOAD = {}

def trigger_workflow(session_id):
    """
    Triggers the workflow execution via REST API.
    """
    conn = http.client.HTTPConnection(WEBSERVER_HOST, WEBSERVER_PORT)
    headers = {'Content-Type': 'application/json'}
    body = json.dumps({
        "workflowId": WORKFLOW_ID,
        "payload": PAYLOAD
    })

    path = f"/api/session/{session_id}/workflow/trigger"

    try:
        conn.request("POST", path, body, headers)
        response = conn.getresponse()
        resp_body = response.read().decode('utf-8')

        if response.status != 200:
            print(f"Error: Failed to trigger workflow (Status: {response.status})")
            print(f"Response: {resp_body}")
            return None

        data = json.loads(resp_body)
        execution_id = data.get('data', {}).get('executionId')

        if not execution_id:
            print("Error: Execution ID not found in response.")
            return None

        print(f"Workflow trigger accepted. Execution ID: {execution_id}")
        return execution_id
    except Exception as e:
        print(f"Error connecting to Jagratha: {e}")
        return None
    finally:
        conn.close()

def monitor_status(session_id, execution_id):
    """
    Monitors the workflow status via Server-Sent Events (SSE).
    """
    path = f"/api/session/{session_id}/workflow/status/{execution_id}/stream"
    conn = http.client.HTTPConnection(WEBSERVER_HOST, WEBSERVER_PORT)

    try:
        conn.request("GET", path, headers={'Accept': 'text/event-stream'})
        response = conn.getresponse()

        if response.status != 200:
            print(f"Error: Failed to connect to status stream (Status: {response.status})")
            return False

        print(f"Monitoring workflow: {WORKFLOW_ID} for session: {session_id}")

        while True:
            line = response.readline().decode('utf-8')
            if not line:
                break

            line = line.strip()
            if line.startswith('data:'):
                data_str = line[5:].strip()
                if not data_str:
                    continue

                progress = json.loads(data_str)
                status = progress.get('status')
                print(f"Current Workflow Status: {status}")

                # Check for terminal statuses
                if status == 'SUCCESS':
                    print("Workflow completed successfully.")
                    return True
                elif status in ('FAILURE', 'ERROR'):
                    print(f"Workflow terminated with status: {status}")
                    return False

    except KeyboardInterrupt:
        print("\nMonitoring cancelled by user.")
        return False
    except Exception as e:
        print(f"Error during status monitoring: {e}")
        return False
    finally:
        conn.close()

def main():
    parser = argparse.ArgumentParser(description="Jagratha Workflow Trigger & Monitor")
    parser.add_argument("--session-id", help="The unique session identifier", required=True)

    args = parser.parse_args()
    session_id = args.session_id

    execution_id = trigger_workflow(session_id)
    if not execution_id:
        sys.exit(1)

    if not monitor_status(session_id, execution_id):
        sys.exit(1)

if __name__ == "__main__":
    main()
