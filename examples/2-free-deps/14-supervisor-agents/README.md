# Supervisors operated by programs

A supervisor of type `http` is operated by a program with its own credential. Three ways to write one:

| File | What |
|---|---|
| `cpu_guard.sh` | A shell script run by `yukta supervisors agent`: it pauses runs while the load is high and resumes only what it paused (`target: held`). Uses **initiative**. |
| `agent.py`, `go/main.go` | The same loop in Python and in Go with the standard library: stream, decide, send. |
| `supervisor.json`, `assignments.json` | The supervisor (`cpu-guard`, allowed to pause and resume on its own) and what it owns: one session. |

```bash
curl -X POST localhost:9858/api/supervisors -H 'Content-Type: application/json' -d @supervisor.json
curl -X PUT localhost:9858/api/supervisors/cpu-guard/assignments -H 'Content-Type: application/json' -d @assignments.json
yukta supervisors credential create cpu-guard > cpu-guard.token        # shown once
yukta supervisors agent --credential-file cpu-guard.token --exec ./cpu_guard.sh
# or: YUKTA_AGENT_TOKEN=$(cat cpu-guard.token) python3 agent.py
```

Change `my-session` in `assignments.json` first. The supervisor checks every directive: a command outside `allowedCommands`, a run it does not own, an initiative without a reason or beyond `maxInitiativePerMinute` is refused and shows in `yukta supervisors decisions --supervisor cpu-guard`. Start with `"dryRun": true` to see what it would do. See `docs/reference/supervisors/engines.mdx`.
