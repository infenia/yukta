# 01 — Hello world

**Use case:** the smallest useful workflow, to check that a server, a session and a run work end to end.

```
CONSTANT_SOURCE ──► PROCESS_EXECUTOR (echo) ──► CONSOLE_TERMINAL
```

**Trigger**
```bash
curl -X POST localhost:8080/api/sessions -H 'Content-Type: application/json' -d @session.json
curl -X POST localhost:8080/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-01-hello","workflowId":"hello"}'
```

**Run:** `./examples/01-hello-world/run.sh`

**Expected:** status `SUCCESS`, and the log line `stdout=Hello from Yukta!`.

**Notes:** every workflow needs one trigger and one terminal. `CONSTANT_SOURCE` emits a single empty message and is the usual trigger for scheduled-style or manual runs.
