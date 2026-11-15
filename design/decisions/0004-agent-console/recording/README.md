<!-- Licensed through REUSE.toml (`**/*.md`). -->
# Recording the agent sessions

Scripts that recorded the fixtures under
`plugins/processors/process-executor/src/test/resources/agent-sessions/` (AC0, #2364). They talk to the
real CLI, so they need an account and cost a few cents; no test runs them. Run from a scratch
directory outside the repository (the agent writes files there):

```bash
python3 -I record_claude_approval_interrupt.py out.jsonl /scratch/work   # claude 2.1.325
python3 -I record_claude_steer_interrupt.py    out.jsonl /scratch/work
python3 -I record_acp.py out.jsonl /scratch/work -- gemini --acp         # gemini 0.46.0
```

The raw recording is then cleaned before it is committed: the session id is replaced by a fixed one,
paths by `/work`, and noise that is not part of the protocol (hook, UI log, thinking token, rate
limit events, `usage`, `modelUsage`, cost, the tool and MCP server lists of `init`) is dropped.
Nothing else is changed. The cleaned lines are `{"t": seconds, "dir": "in"|"out", "frame": {...}}`;
`in` is what Yukta would write to the agent, `out` what the agent printed.

The `spike/` directory holds the PTY spikes (go-pty with `vt10x` or `charmbracelet/x/vt`).
