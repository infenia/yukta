# 06 — HTTP health check with an alert path

**Use case:** a monitoring or smoke check. The workflow calls an endpoint and routes to an OK report or an alert, based on the HTTP result.

```
CONSTANT_SOURCE ──► HTTP_REQUEST (routeByStatus)
                 ├── success ──► echo HEALTH OK ──────────┐
                 └── failure ──► echo HEALTH ALERT ───────┴──► CONSOLE_TERMINAL
```

Two workflows in one session show both routes:
- `health-ok` probes `http://localhost:8080/api/plugins` (answers 200).
- `health-down` probes `http://127.0.0.1:9/health` (connection refused), so it takes the failure port.

**Trigger**
```bash
curl -X POST localhost:8080/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-06-health-check","workflowId":"health-ok"}'
```

**Run:** `./examples/06-http-health-check/run.sh`

**Expected:** `HEALTH OK` for `health-ok`, `HEALTH ALERT` for `health-down`. Both runs finish `SUCCESS` because the failure port handled the error.

**Notes:** if your server has `yukta.api-key` set, add a header to the probe in `session.json` (`headers` config) or the health-ok probe will get a 401 and take the alert path.
