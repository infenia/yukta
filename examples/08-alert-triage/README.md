# Alert triage

## The pain point

An alert fires at 3 a.m. Most of the time restarting the service fixes it, and the person
paged does exactly that. Low-priority alerts wake nobody but still need a trace. This workflow
tries the restart for you, a few times and no more, and pages the on-call only when it did not
work, with an incident record already open and a clear note of who took it.

## The graph

`alert`:

```
API_TRIGGER ─► BRANCH by-severity ─┬─ log-only (low, unknown) ─► MAPPER ──────────────┐
  alert JSON                       │                                                   │
                                   └─ fix (critical, high) ─► LOOP_PREDICATE try-fix   │
                                          ≤3 attempts, 1 s apart                       │
                                          each = SUB_WORKFLOW restart-and-check        │
                                                  │                                    │
                                          BRANCH did-it-heal                           │
                                          ├─ healed ─► MAPPER ─► FILE_WRITE write-record ─► end
                                          │                          ▲
                                          └─ still-failing           └──────────────┐
                                                  │                                 │
                                          RECIPIENT_LIST escalate                   │
                                           ├─ page ─► MAPPER ─► NOTIFY_TERMINAL     │
                                           └─ incident ─► MAPPER ─► FILE_WRITE incidents.log
                                                            │
                                                  WAIT_FOR_SIGNAL wait-ack ─► MAPPER format-ack ─┘
```

`restart-and-check` (one attempt):

```
API_TRIGGER ─► PROCESS_EXECUTOR ─► PROCESS_EXECUTOR ─┬─ success ─► MAPPER result-up ──┐
                restart-service     check-health      └─ failure ─► MAPPER result-down ┴► end
```

## What each node does

| Node | Does |
|---|---|
| `by-severity` (BRANCH) | `critical` and `high` go to `fix`; anything else, including an unknown value, is only logged |
| `format-low` | Builds the `LOGGED alert=… severity=…` line for `alerts.log` |
| `try-fix` (LOOP_PREDICATE) | Runs `restart-and-check` up to 3 times (`maxIterations`), 1 second apart, at most 2 minutes (`maxDuration`). Stops early when the result says `healthy` |
| `restart-service` (PROCESS_EXECUTOR) | Stand-in for a restart: appends a line to a per-alert state file and prints how many restarts so far |
| `check-health` (PROCESS_EXECUTOR) | Stand-in for a health check, routed on exit code: a service named `flaky…` is up (exit 0) from the 2nd restart, any other is never up (exit 1) |
| `result-up`, `result-down` | Return `alertId`, `service`, `severity`, `restarts`, `healthy` |
| `did-it-heal` (BRANCH) | `healed` is logged as `AUTO-RESOLVED`; `still-failing` escalates |
| `escalate` (RECIPIENT_LIST) | Does two things at once: page and open an incident |
| `format-page`, `page-oncall` (NOTIFY_TERMINAL) | POSTs `{"text": "PAGE on-call: …"}` to a webhook |
| `format-incident`, `open-incident` | Append `INCIDENT OPENED status=open …` to `incidents.log` |
| `wait-ack` (WAIT_FOR_SIGNAL) | Holds for the signal `ack` for 15 minutes. Decision `approve` means acknowledged, `decline` means the on-call hands it on, no answer means timeout |
| `format-ack`, `write-record` | Append the result to the incident log (`ACKNOWLEDGED`, `DECLINED` or `NO-ACK`) |

## Make it yours

- **Real restart and check:** edit the two `sh -c` commands in `restart-and-check`: put your
  restart (for example `systemctl restart ...`) in `restart-service` and your health probe
  (`curl -fsS .../health`) in `check-health`; its exit code drives the `success` and `failure`
  edges. Any executable you use must be on the server's `yukta.process.allowed-commands`. The loop stays as it is.
- **How many tries:** `maxIterations`, `delayInterval` and `maxDuration` in `try-fix`. Keep
  `timeoutSeconds` above `maxDuration`.
- **Severities:** the `cases` of `by-severity`. Add a `warning` row to remediate warnings as well.
- **Page:** change `url` of `page-oncall` to your chat or paging webhook (https; plain http
  works only for localhost). The text is built in `format-page`. For several channels add
  ports to `escalate`.
- **Incident record:** `open-incident` writes a file. Swap it for an `HTTP_REQUEST` to your
  ticket system; keep `format-incident` to shape the body. Change `baseDir` to a folder the server may write.
- **Acknowledgement:** `timeoutSeconds` (900) in `wait-ack`. On `decline` or timeout the
  record says "paging secondary on-call"; add a second `NOTIFY_TERMINAL` after `format-ack`
  to do that for real. A literal `reject` decision fails the wait node and the run (`onReject: FAIL`).
- **LLM:** none is needed. To add context to the page, put an `LLM_CALL` between `escalate`
  and `format-page` that turns the alert text into a two-line summary. Do not let a model
  decide whether to restart.

### Webhook variant

Monitoring tools such as Alertmanager or Grafana post JSON and cannot send a Yukta start
request. Replace the first node with a signed webhook:

```json
{ "nodeId": "alert-in", "type": "WEBHOOK_TRIGGER",
  "config": { "path": "alert", "secret": "decrypted:alert-hook", "signatureHeader": "X-Signature-256" } }
```

Set `YUKTA_SECRET_ALERT_HOOK` on the server. The message payload is then `{"body": "<raw text>", "headers": {...}}`,
so add a `MAPPER` in `SCRIPT` mode (needs the `script-js` module) with `JSON.parse(payload.body)` to get
`alertId`, `service` and `severity` back. Call it with the body signed by HMAC-SHA256:

```bash
body='{"alertId":"A-1","service":"api","severity":"critical"}'
sig=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$YUKTA_SECRET_ALERT_HOOK" | awk '{print $2}')
curl -X POST localhost:9858/hooks/example-08-alert-triage/alert \
  -H "X-Signature-256: sha256=$sig" -d "$body"
```

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-08-alert-triage","workflowId":"alert","payload":{"alertId":"A-1","service":"dead-db","severity":"critical"}}'
```

Acknowledge as the on-call (the pending approval also shows in the UI):

```bash
curl localhost:9858/api/sessions/example-08-alert-triage/approvals
curl -X POST "localhost:9858/api/executions/<executionId>/nodes/wait-ack/signal?name=ack&decision=approve"
```

## Run

The server must allow `PROCESS_EXECUTOR` to run `sh` (`yukta.process.allowed-commands=sh`; the narrowest set this example needs),
and `FILE_WRITE` under `/tmp/yukta-examples`.

```bash
./examples/08-alert-triage/run.sh
```

The script starts a small local pager (python3, port 9877) that receives the page, then sends
alerts and plays the on-call: low and unknown severity, a service that recovers, one that
does not (acknowledged, declined, rejected, and unanswered with the wait shortened to 3 seconds).
It re-applies the real session at the end.

## Expected result

```
alerts.log      LOGGED alert=L1-… service=web-cache severity=low
                AUTO-RESOLVED alert=F1-… service=flaky-api restarts=2
incidents.log   INCIDENT OPENED status=open alert=D1-… service=dead-db severity=high restarts=3
                INCIDENT ACKNOWLEDGED by on-call | INCIDENT OPENED status=open alert=D1-… …
pager receives  {"text":"PAGE on-call: dead-db still failing after 3 restarts (high, alert D1-…)"}
```

The script prints `PASS 08-alert-triage` and exits 0.
