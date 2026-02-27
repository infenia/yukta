# Yukta Claude Hook Simulator 🛠️

This simulator allows you to test Yukta's client scripts by mimicking the way Claude Code invokes hooks. It reads a scenario defined in a JSON file and executes the corresponding hook scripts in sequence, passing the specified data via `stdin`.

## 📂 Structure

- `simulator.py`: The main simulation engine.
- `scenarios/`: Directory containing JSON scenario definitions.

## 🚀 Usage

### Run a scenario
```bash
python3 simulator.py scenarios/happy_path.json
```

### Overriding Server Host and Port
By default, the scripts use `localhost:8080`. You can override these using command-line arguments:
```bash
python3 simulator.py scenarios/happy_path.json --host 192.168.1.10 --port 9090
```
Or via environment variables:
```bash
JAGRATHA_HOST=myserver JAGRATHA_PORT=8888 python3 simulator.py scenarios/happy_path.json
```

## 📝 Scenario Format

A scenario is a JSON file with a `name` and a list of `steps`. Each step specifies a `hook` type and the `data` payload to be sent to the hook script.

### Supported Hook Types
- `init` (or `SessionStart`)
- `save` (or `PostToolUse`)
- `trigger` (or `Stop`)

### Example Scenario
```json
{
  "name": "My Test Scenario",
  "steps": [
    {
      "hook": "SessionStart",
      "data": {
        "session_id": "test-session",
        "cwd": "/path/to/project"
      }
    },
    {
      "hook": "PostToolUse",
      "data": {
        "session_id": "test-session",
        "cwd": "/path/to/project",
        "tool_input": {
          "file_path": "/path/to/project/src/Main.java"
        }
      }
    },
    {
      "hook": "Stop",
      "data": {
        "session_id": "test-session"
      }
    }
  ]
}
```

## 💡 How it Works
The simulator invokes the scripts found in `client/scripts/`. It pipes the `data` object from each step as a JSON string into the script's `stdin`, mimicking the behavior of Claude Code's command hooks.

If any step fails (non-zero exit code from the script), the simulator stops immediately.
