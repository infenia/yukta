# Returns and refund fraud screen

## The pain point

Refund fraud costs money, but checking every return by hand is slow and annoys honest
customers. You want small, ordinary returns paid at once, risky ones looked at by a person,
and a customer message that never reveals your fraud rules or their card details.

## The graph

```
return-request ─► validate ──invalid──► mark-invalid ─────────────────────────────┐
                     │                                                            │
                     ▼                                                            │
                   risk ─► route ──low──► auto-approve ─┐                         │
                              │                          ├► refund-line ─► refund ──┤
                              └─high─► review-queue      │                         │
                                         ├ answer ─► after-review                  │
                                         │    ├ approved ─► reviewer-approve ─┘    │
                                         │    ├ denied ───► reviewer-deny ─────────┤
                                         │    └ timed-out ► review-timeout ────────┤
                                         └ reject ─► fraud-escalation (OUTCOME ESCALATE)
                                                                                    │
                 redact ◄───────────────────────────────────────────────────────────┘
                    └─► compose ─► notify-customer (FILE_WRITE outbox) ─► end
```

## What each node does

| Node | Plugin | Job |
|---|---|---|
| `return-request` | `API_TRIGGER` | Takes the return as the start payload |
| `validate` | `VALIDATE` | Checks required fields and types; bad input leaves on `invalid` |
| `risk` | `MAPPER` | Adds `riskScore`: amount over 200 (+20) or 500 (+35), return rate over 30% (+20) or 50% (+40), account younger than 30 days (+25), reason `not_received` (+15) |
| `route` | `BRANCH` | Score under 30 is `low`, otherwise `high` |
| `auto-approve` | `MAPPER` | Marks the return `REFUND_APPROVED` by rules |
| `review-queue` | `WAIT_FOR_SIGNAL` | Holds high-risk returns until signal `review` arrives (24 h, then it continues as `timeout`) |
| `after-review` | `BRANCH` | Reads `metadata['signal.decision']`: `timeout`, `deny`, or anything else (approve) |
| `reviewer-approve`, `reviewer-deny`, `review-timeout` | `MAPPER` | Record the human result, or tell the customer the review is taking longer |
| `fraud-escalation` | `OUTCOME_TERMINAL` | A `reject` signal hands the case to the fraud team and ends the run as `ESCALATE`; no refund, no customer message |
| `refund-line`, `refund` | `MAPPER`, `FILE_WRITE` | Write one refund line to `/tmp/yukta-example-returns-fraud/refunds.log` (stand-in for a payment API call) |
| `redact` | `CONTENT_FILTER` | Removes `cardLast4`, `riskScore`, `decidedBy` and the history counters |
| `compose`, `notify-customer` | `MAPPER`, `FILE_WRITE` | Append the customer message to `outbox.log` (stand-in for an email) |

`reject` is special in `WAIT_FOR_SIGNAL`: it fails the node and `engine.onError: ROUTE` sends the message to the `error` port.
Use `decision=deny` to refuse a refund and still tell the customer.

## Make it yours

- Risk rules and thresholds: edit the `riskScore` expression in `risk` and the cases in `route`.
- Fields to hide from the customer: `paths` in `redact`.
- Paying the refund: replace `refund` with an `HTTP_REQUEST` to your payment provider (`method: POST`, a `body` built in `refund-line`, `failureMode: CONTINUE` with `routeByStatus` so a failed payment is routed, not lost).
- Customer message: `note` fields in the decision `MAPPER` nodes and the `compose` expression; replace `notify-customer` with `HTTP_REQUEST` or `NOTIFY_TERMINAL`.
- LLM: the risk score is fixed rules. A model fits as an extra step before `route`, for example `LLM_CALL` summarising the customer's return reason for the reviewer, or `ANOMALY` (needs the decision module and a model) as a second risk signal added to `riskScore`.

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"example-05-returns-fraud\",\"workflowId\":\"returns\",\"payload\":$(cat high.json)}"

curl localhost:9858/api/sessions/example-05-returns-fraud/approvals
curl -X POST "localhost:9858/api/executions/<executionId>/nodes/review-queue/signal?name=review&decision=approve"
```

## Run

```bash
./examples/05-returns-fraud/run.sh
```

`run.sh` sends `low.json`, `invalid.json` and `high.json` (four times: approve, deny, reject, timeout) and acts as the reviewer.

## Expected result

Ends with `PASS 05-returns-fraud`: two refunds in `refunds.log` (the low-risk return and the approved high-risk one), and customer messages without card digits or scores.
