import json
import os
import subprocess
import sys
import argparse

def run_script(script_path, payload, host=None, port=None):
    """Runs a hook script with the given payload on stdin."""
    env = os.environ.copy()
    if host:
        env["JAGRATHA_HOST"] = host
    if port:
        env["JAGRATHA_PORT"] = str(port)

    process = subprocess.Popen(
        [sys.executable, script_path],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env
    )

    stdout, stderr = process.communicate(input=json.dumps(payload))

    return process.returncode, stdout, stderr

def main():
    parser = argparse.ArgumentParser(description="Jagratha Claude Hook Simulator")
    parser.add_argument("scenario", help="Path to the scenario JSON file")
    parser.add_argument("--host", help="Jagratha server host (overrides JAGRATHA_HOST)")
    parser.add_argument("--port", type=int, help="Jagratha server port (overrides JAGRATHA_PORT)")

    args = parser.parse_args()

    if not os.path.exists(args.scenario):
        print(f"Error: Scenario file {args.scenario} not found.")
        sys.exit(1)

    try:
        with open(args.scenario, 'r') as f:
            scenario = json.load(f)
    except Exception as e:
        print(f"Error parsing scenario file: {e}")
        sys.exit(1)

    print(f"====================================================")
    print(f"🚀 Running Jagratha Simulator Scenario: {scenario.get('name', 'Unnamed')}")
    print(f"====================================================")

    # Scripts are located in ../scripts relative to this file
    base_dir = os.path.dirname(os.path.abspath(__file__))
    scripts_dir = os.path.abspath(os.path.join(base_dir, "..", "scripts"))

    hook_to_script = {
        "init": "init_session.py",
        "SessionStart": "init_session.py",
        "save": "save_file.py",
        "PostToolUse": "save_file.py",
        "trigger": "trigger_workflow.py",
        "Stop": "trigger_workflow.py"
    }

    steps = scenario.get("steps", [])
    if not steps:
        print("No steps found in scenario.")
        sys.exit(0)

    for i, step in enumerate(steps):
        hook_type = step.get("hook")
        data = step.get("data", {})

        script_name = hook_to_script.get(hook_type)
        if not script_name:
            print(f"\n❌ Step {i+1}: Unknown hook type '{hook_type}'. Stopping.")
            sys.exit(1)

        script_path = os.path.join(scripts_dir, script_name)
        if not os.path.exists(script_path):
            print(f"\n❌ Step {i+1}: Script {script_name} not found in {scripts_dir}.")
            sys.exit(1)

        print(f"\n[Step {i+1}/{len(steps)}] Hook: {hook_type} -> {script_name}")

        returncode, stdout, stderr = run_script(script_path, data, args.host, args.port)

        if stdout:
            print(stdout.strip())
        if stderr:
            print(f"STDERR:\n{stderr.strip()}", file=sys.stderr)

        if returncode != 0:
            print(f"\n🛑 Step {i+1} failed with return code {returncode}. Aborting scenario.")
            sys.exit(returncode)

    print(f"\n====================================================")
    print(f"✅ Scenario completed successfully!")
    print(f"====================================================")

if __name__ == "__main__":
    main()
