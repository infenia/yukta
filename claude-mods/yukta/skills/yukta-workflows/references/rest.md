# Drive Yukta with REST (no MCP tools, no CLI)

Base URL `http://localhost:9858`. With keys configured, send `Authorization: Bearer <key>` (or `X-API-Key`); read the key from the
environment, never paste it into a command you show the user. Responses are `{"data": ...}` envelopes, errors carry a `message`.

```bash
curl -sS "$YUKTA_SERVER_URL/api/me"                                  # who am I (role, permissions)
curl -sS "$YUKTA_SERVER_URL/api/sessions"                            # list sessions
curl -sS -X POST "$YUKTA_SERVER_URL/api/workflow/start" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"sessionId":"demo","workflowId":"main","payload":{}}'        # start a run
curl -sS "$YUKTA_SERVER_URL/api/executions/<executionId>"            # status
curl -sS "$YUKTA_SERVER_URL/api/sessions/<sessionId>/executions/<executionId>/results"
curl -sS "$YUKTA_SERVER_URL/api/executions/<executionId>/nodes/<nodeId>/output?tail=100"
curl -sS -X POST "$YUKTA_SERVER_URL/api/executions/<executionId>/stop"
```

Roles: `GET` needs `VIEWER`; starting and steering runs needs `OPERATOR`; applying sessions, rollbacks and scheduler limits need `ADMIN`.
The full list is at `<server>/swagger-ui.html` and in the docs under API. The same ask-first rules as the MCP skill apply.
