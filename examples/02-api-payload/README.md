# 02 — Trigger over REST with a payload

**Use case:** another system (a script, a CI job, a service) starts a workflow and passes its own data in the request. The workflow maps that data into a result.

```
API_TRIGGER ──► MAPPER (PROJECTION) ──► CONSOLE_TERMINAL
```

**Trigger**
```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-02-api-payload","workflowId":"greet-user","payload":{"name":"Ada","priority":"high"}}'
```

**Run:** `./examples/02-api-payload/run.sh`

**Expected:** status `SUCCESS`, and the mapped record `{greeting=Hello, Ada, priority=high}`.
