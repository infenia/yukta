# 07 — Fan-out notification

**Use case:** one event must reach several channels at once (Slack, email, pager). `RECIPIENT_LIST` sends a copy to each named port, and the channels run in parallel.

```
ONE_SHOT ──► RECIPIENT_LIST (STATIC: slack, email, pager)
                 ├── slack ──► notify ──┐
                 ├── email ──► notify ──┼──► CONSOLE_TERMINAL
                 └── pager ──► notify ──┘
```

Replace each `echo` with a real delivery step (an HTTP_REQUEST to a webhook, a mail script, and so on).

**Trigger**
```bash
curl -X POST localhost:8080/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-07-fan-out","workflowId":"notify"}'
```

**Run:** `./examples/07-fan-out-notify/run.sh`

**Expected:** status `SUCCESS` and three lines: `notified: slack`, `notified: email`, `notified: pager`.
