# 08 — Retry until ready (bounded)

**Use case:** wait for a dependency to become ready (a service starting up, a lock to free, an artifact to appear). The check repeats until an exit condition holds, with hard limits so it cannot run forever.

```
ONE_SHOT ──► LOOP_PREDICATE (target: PROCESS_EXECUTOR, every 1s) ──► CONSOLE_TERMINAL
```

Key settings:
- `exitCondition`: SpEL over `#iterationCount` and `#elapsedTimeMs`. Here it stops after 3 iterations; for a real check use something like `payload.exitCode == 0`.
- `maxIterations`, `maxDuration`: hard caps.
- `delayInterval`: ISO-8601 pause between iterations (`PT1S`).
- `failureStrategy`: `ABORT`, `RETRY`, `SKIP` or `ESCALATE`.

**Trigger**
```bash
curl -X POST localhost:8080/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-08-retry-until-ready","workflowId":"wait-ready"}'
```

**Run:** `./examples/08-retry-until-ready/run.sh`

**Expected:** `Iteration 1`, `Iteration 2`, `Iteration 3`, `Exit condition met`, then status `SUCCESS`.
