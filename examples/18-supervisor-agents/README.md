# Supervisors operated by programs

A supervisor of type `http` is operated by a program with its own credential. Three ways to write one:

| File | What |
|---|---|
| `cpu_guard.sh` | A shell script run by `yukta supervisors agent`: it pauses runs while the load is high and resumes only what it paused (`target: held`). Uses **initiative**. |
| `agent.py`, `go/main.go` | The same loop in Python and in Go with the standard library: stream, decide, send. |
| `supervisor.json`, `attachment.json` | The supervisor (`cpu-guard`, allowed to pause and resume on its own) and its attachment to a session. |

```bash
curl -X POST localhost:9858/api/supervisors -H 'Content-Type: application/json' -d @supervisor.json
curl -X POST localhost:9858/api/supervisors/attachments -H 'Content-Type: application/json' -d @attachment.json
yukta supervisors credential create cpu-guard > cpu-guard.token        # shown once
yukta supervisors agent --credential-file cpu-guard.token --exec ./cpu_guard.sh
# or: YUKTA_AGENT_TOKEN=$(cat cpu-guard.token) python3 agent.py
```

Change `my-session` in `attachment.json` first. The supervisor checks every directive: a command outside `allowedCommands`, a run it does not own, an initiative without a reason or beyond `maxInitiativePerMinute` is refused and shows in `yukta supervisors decisions --supervisor cpu-guard`. Start with `"dryRun": true` to see what it would do. See `docs/running/supervisor-engines.mdx`.
