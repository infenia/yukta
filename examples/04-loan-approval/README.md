# Loan approval with a human gate

## The pain point

Most loan applications are obvious: very good or very bad. Sending all of them to an
underwriter wastes time, and deciding all of them by rule is risky. You also have to tell the
applicant the result without copying their tax id or credit data into an email or a chat message.

## The graph

```
application ─► validate ──invalid──► mark-invalid ─────────────────────────────┐
                  │                                                            │
                  ▼                                                            │
                score ─► route ──approve──► auto-approve ──────────────────────┤
                           │ ──decline──► auto-decline ──────────────────────┤
                           │                                                   │
                           └──review──► underwriter (WAIT_FOR_SIGNAL)          │
                                          ├─ answer ─► after-review            │
                                          │            ├ approved ► human-approve ┤
                                          │            ├ declined ► human-decline ┤
                                          │            └ timed-out ► review-timeout ┤
                                          └─ reject ─► rejected-stop (OUTCOME FAILED)
                                                                               │
              redact ◄─────────────────────────────────────────────────────────┘
                 └─► compose ─► notify (FILE_WRITE outbox) ─► end
```

## What each node does

| Node | Plugin | Job |
|---|---|---|
| `application` | `API_TRIGGER` | Takes the application as the start payload |
| `validate` | `VALIDATE` | Checks required fields and types; bad input leaves on `invalid` |
| `score` | `MAPPER` | Adds `score` (0 to 100) from credit score, loan to income and debt to income |
| `route` | `BRANCH` | `score >= 75` approve, `score < 40` or credit score under 500 decline, the rest review |
| `auto-approve`, `auto-decline` | `MAPPER` | Record the decision, who made it and why |
| `underwriter` | `WAIT_FOR_SIGNAL` | Holds the case until signal `underwriter` arrives (24 h, then it continues as `timeout`) |
| `after-review` | `BRANCH` | Reads `metadata['signal.decision']`: `timeout`, `decline` or anything else (approve) |
| `human-approve`, `human-decline`, `review-timeout` | `MAPPER` | Record the human result, or escalate when nobody answered |
| `rejected-stop` | `OUTCOME_TERMINAL` | A `reject` signal pulls the case out of the automatic flow and ends the run as `FAILED`; nothing is sent |
| `redact` | `CONTENT_FILTER` | Removes `ssn`, `income`, `creditScore`, `monthlyDebt` and `loanAmount` |
| `compose` | `MAPPER` | Builds the notification `text` |
| `notify` | `FILE_WRITE` | Appends the text to `/tmp/yukta-example-loan-approval/outbox.log` (stand-in for an email or chat call) |

`reject` is special in `WAIT_FOR_SIGNAL`: it fails the node, and the node's `engine.onError: ROUTE`
sends the message to the `error` port. To record a human "no" and still notify the applicant,
send `decision=decline` instead. Any decision other than `reject` and `timeout` counts as approval
unless `after-review` names it.

## Make it yours

- Scoring rules: edit the `score` expression and the thresholds in `route`.
- Required fields: edit `required` and `types` in `validate`.
- Fields that must not leave the building: edit `paths` in `redact`.
- Where the notice goes: replace `notify` with `HTTP_REQUEST` (your mail or chat API) or keep a `FILE_WRITE` and add `NOTIFY_TERMINAL`.
- How long the underwriter has: `timeoutSeconds` and `onTimeout` on `underwriter`.
- LLM: the scoring is fixed rules. To add model judgement, put an `LLM_CALL` (or `SCORE` / `CLASSIFY` with a decision model) between `score` and `route` that writes a second signal, such as a free-text risk note for the underwriter. Keep the fixed rules as the gate.

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"example-04-loan-approval\",\"workflowId\":\"loan\",\"payload\":$(cat review.json)}"

# a borderline case now waits; answer it
curl localhost:9858/api/sessions/example-04-loan-approval/approvals
curl -X POST "localhost:9858/api/executions/<executionId>/nodes/underwriter/signal?name=underwriter&decision=approve"
```

## Run

```bash
./examples/04-loan-approval/run.sh
```

`run.sh` sends `approve.json`, `decline.json`, `invalid.json` and `review.json` (four times: approve, decline,
reject, timeout), and acts as the underwriter by polling the approvals list and sending the signal.

## Expected result

Ends with `PASS 04-loan-approval`. The outbox holds one line per decision and none of the sensitive values.
