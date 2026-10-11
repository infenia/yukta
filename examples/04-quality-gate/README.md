# 04 — Quality gate for AI-agent changes

**Use case:** an AI agent (or a CI job) submits change metrics and gets a pass or fail decision. Good for "do not merge unless coverage is at least 80% and lint is clean".

```
API_TRIGGER ──► BRANCH (EXPRESSION)
                 ├── pass ──► echo QUALITY GATE PASSED ──┐
                 └── fail ──► echo QUALITY GATE FAILED ──┴──► CONSOLE_TERMINAL
```

Rules live in the `cases` map as SpEL predicates over the payload:
`payload.coverage >= 80 && payload.lintErrors == 0` → `pass`; the complement → `fail`.

**Trigger**
```bash
curl -X POST localhost:8080/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-04-quality-gate","workflowId":"gate","payload":{"coverage":91,"lintErrors":0}}'
```

**Run:** `./examples/04-quality-gate/run.sh` sends `pass.json` and `fail.json`.

**Expected:** `QUALITY GATE PASSED` for the first and `QUALITY GATE FAILED` for the second.
