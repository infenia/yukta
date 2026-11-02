# PR risk gate with a human reviewer

## The pain point

Most pull requests are obvious: a small, tested change, or a huge untested one touching `migrations/`
and `auth/`. Sending all of them to a reviewer wastes time, and merging all of them by rule is risky.
You also have to post the result to a chat channel without leaking the `ghp_` token or AWS key that
someone pasted into the PR description.

## The graph

```
pr ─► validate ──invalid──► mark-invalid ──────────────────────────────────────┐
         │                                                                     │
         ▼                                                                     │
       score ─► route ──merge──► auto-merge ───────────────────────────────────┤
                  │ ──block──► auto-block ────────────────────────────────────┤
                  │                                                            │
                  └──review──► reviewer (WAIT_FOR_SIGNAL)                      │
                                 ├─ answer ─► after-review                     │
                                 │            ├ approved ► human-approve ──────┤
                                 │            ├ declined ► human-decline ──────┤
                                 │            └ timed-out ► review-timeout ────┤
                                 └─ reject ─► rejected-stop (OUTCOME FAILED)
                                                                               │
     redact ◄──────────────────────────────────────────────────────────────────┘
        └─► compose ─► notify (FILE_WRITE outbox) ─► end
```

## What each node does

| Node | Plugin | Job |
|---|---|---|
| `pr` | `API_TRIGGER` | Takes the PR facts as the start payload |
| `validate` | `VALIDATE` | Checks required fields and types; bad input leaves on `invalid` |
| `score` | `MAPPER` | Adds `score` (0 to 100, higher is safer) from lines changed, files under `migrations/` or `auth/`, tests added and coverage delta |
| `route` | `BRANCH` | `score >= 75` merge, `score < 40` or 3+ sensitive files block, the rest review |
| `auto-merge`, `auto-block` | `MAPPER` | Record the decision, who made it and why |
| `reviewer` | `WAIT_FOR_SIGNAL` | Holds the PR until signal `reviewer` arrives (24 h, then it continues as `timeout`) |
| `after-review` | `BRANCH` | Reads `metadata['signal.decision']`: `timeout`, `decline` or anything else (approve) |
| `human-approve`, `human-decline`, `review-timeout` | `MAPPER` | Record the human result, or escalate to the code owner when nobody answered |
| `rejected-stop` | `OUTCOME_TERMINAL` | A `reject` signal pulls the PR out of the automatic flow and ends the run as `FAILED`; nothing is sent |
| `redact` | `CONTENT_FILTER` | Removes `githubToken` and `awsKey` |
| `compose` | `MAPPER` | Builds the notification `text` |
| `notify` | `FILE_WRITE` | Appends the text to `/tmp/yukta-example-pr-risk-gate/outbox.log` (stand-in for a chat or email call) |

`reject` is special in `WAIT_FOR_SIGNAL`: it fails the node, and the node's `engine.onError: ROUTE`
sends the message to the `error` port. To record a human "no" and still notify the channel,
send `decision=decline` instead. Any decision other than `reject` and `timeout` counts as approval
unless `after-review` names it.

## Make it yours

- Scoring rules: edit the `score` expression and the thresholds in `route`.
- Required fields: edit `required` and `types` in `validate`.
- Fields that must not leave the building: edit `paths` in `redact`.
- Where the notice goes: replace `notify` with `HTTP_REQUEST` (your Slack or mail API) or keep a `FILE_WRITE` and add `NOTIFY_TERMINAL`.
- How long the reviewer has: `timeoutSeconds` and `onTimeout` on `reviewer`.
- LLM: the scoring is fixed rules. To add model judgement, put an `LLM_CALL` (or `SCORE` / `CLASSIFY` with a decision model) between `score` and `route` as a PR-risk summariser that writes a free-text risk note for the reviewer. Keep the fixed rules as the gate.

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"example-04-pr-risk-gate\",\"workflowId\":\"pr\",\"payload\":$(cat borderline.json)}"

# a borderline PR now waits; answer it
curl localhost:9858/api/sessions/example-04-pr-risk-gate/approvals
curl -X POST "localhost:9858/api/executions/<executionId>/nodes/reviewer/signal?name=reviewer&decision=approve"
```

## Run

```bash
./examples/04-pr-risk-gate/run.sh
```

`run.sh` sends `low-risk.json`, `high-risk.json`, `invalid.json` and `borderline.json` (four times: approve, decline,
reject, timeout), and acts as the reviewer by polling the approvals list and sending the signal.

## Expected result

Ends with `PASS 04-pr-risk-gate`. The outbox holds one line per decision and none of the secrets.
