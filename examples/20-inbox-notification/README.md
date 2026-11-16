# 20 — Inbox notification

**Use case:** a workflow tells people something without a webhook: it raises a notification in the Yukta inbox (the bell, the Inbox page, `yukta notifications list`, `yukta notify`).

```
CONSTANT_SOURCE ──► PROCESS_EXECUTOR (echo) ──► NOTIFY_TERMINAL (target: inbox)
```

**Trigger**
```bash
curl -X POST localhost:9858/api/sessions -H 'Content-Type: application/json' -d @session.json
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-20-inbox","workflowId":"report"}'
```

**Run:** `./examples/20-inbox-notification/run.sh`

**Expected:** status `SUCCESS`, then `GET /api/notifications` (or `yukta notifications list`) shows "Nightly report ready", category `workflow`, for operators.

**Notes:** `target: inbox` needs no `url`. The session, workflow, run and node of the notification are the node's own; the workflow cannot choose them. A run may raise 20 a minute and 200 in all. A plugin of your own raises the same through `NotificationGateway`. Without `target`, `NOTIFY_TERMINAL` posts to a webhook as before.
