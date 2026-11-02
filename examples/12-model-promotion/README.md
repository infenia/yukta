# Model promotion with a reviewer gate

## The pain point

A retrained model candidate is promoted by a thread: someone pastes metrics into chat, a reviewer
says "looks fine", and the registry gets updated by hand. Obvious failures (accuracy under the floor,
p95 latency over budget, a bias gap over the limit) waste the reviewer's time, nobody counts how many
retrains a model has had, and "approved" lives in a chat scroll. This workflow runs the cheap eval
checks first, asks the ML reviewer only about candidates that pass them, sends notes back for the next
retrain, stops after three rounds, and promotes only what was approved.

## The graph

```
review (parent workflow)
API_TRIGGER ─► VALIDATE ─┬─ ok ──► start-round-1 ─► round-1 ─┬─ success ─────────────────────────┐
                         │                                   └─ retry ─► start-round-2 ─► round-2 ─┬─ success ──┤
                         └─ invalid ─► bad-candidate ──┐                                      └─ retry ─► start-round-3 ─► round-3 ─┬─ success ─┤
                                                       │                                                                          └─ retry ─┐  │
              (failure of any round, i.e. reviewer reject) ────────────────────────────────────────────────────────────────────────────────────┤  │
                                                       ▼                                                                                      ▼  ▼
                                      notify-stop ◄── stamp-notify-stop                                         stamp-promoted ─► promote ─► stamp-notify-ok ─► notify-ok ─► done
                                           └─► stopped (FAILED)

round (child workflow, one promotion round)
API_TRIGGER ─► retrain ─► check ─► gate (BRANCH)
                                    ├─ clean ─► reviewer (WAIT_FOR_SIGNAL "promote") ─► decide (BRANCH)
                                    │                                                    ├─ approved ─► approved-payload ─► SUCCESS
                                    │                                                    └─ retrain  ─► changes-payload ──► RETRY
                                    └─ fix   ─► auto-notes ─────────────────────────────────────────────────────────────► RETRY
```

Yukta workflows have no cycles, so the rounds are three `SUB_WORKFLOW` nodes that run the same
`round` workflow. Each round ends with an outcome terminal, and the parent routes on it
(`routeByOutcome`).

## What each node does

| Node | Type | What it does |
|---|---|---|
| `validate` | VALIDATE | Needs `name`, `version`, `accuracy`, `latencyMs` and `biasGap`; `maxLatencyMs` is optional. A bad candidate takes the `invalid` port |
| `start-round-N` | MAPPER | Builds the round input: candidate metrics, round number, notes from the last round |
| `round-N` | SUB_WORKFLOW | Runs `round`; leaves on `success` (approved), `retry` (retrain wanted) or `failure` (rejected) |
| `retrain` | MAPPER (stand-in) | Plays the training job: round 1 evaluates the metrics as submitted, later rounds return a canned improvement (accuracy +0.03 per round) |
| `check` | MAPPER | Lists failed checks: accuracy floor 0.90, p95 latency ceiling `maxLatencyMs`, bias gap limit 0.05 |
| `gate` | BRANCH | `clean` goes to the reviewer; `fix` skips the reviewer and asks for a retrain |
| `reviewer` | WAIT_FOR_SIGNAL | Waits for the signal `promote`, up to 15 minutes |
| `decide` | BRANCH | `approve` promotes, any other decision is "retrain"; the signal's `data` is the notes |
| `stamp-promoted`, `promote` | MAPPER, FILE_WRITE | Writes `<name>-<version>.json` to the registry folder (stand-in for a model-registry call) |
| `stamp-notify-*`, `notify-*` | MAPPER, FILE_WRITE | Appends a line to `outbox.log` (stand-in for a chat message) |
| `done`, `stopped` | OUTCOME_TERMINAL | End of the happy path and of the "not promoted" path |

The reviewer has three answers: `approve`, `retrain` with notes in `data`, and `reject`. A `reject`
signal fails the round (the node's `onReject` is `FAIL`), and the parent sends the "rejected"
notice. `retrain` is a normal decision name, not `reject`, because a rejected message is
dropped and its notes would be lost.

## Make it yours

- **Thresholds:** edit the accuracy floor `0.90` and the bias limit `0.05` in the `check` node's
  `problems` expression, and the default latency ceiling in `start-round-1`
  (`payload['maxLatencyMs'] != null ? ... : 200`). Add more checks by appending to `problems`.
- **Rounds:** to allow 5 rounds, copy a `start-round-N` / `round-N` pair and wire its `retry`
  and `failure` ports like the others.
- **Eval report summariser:** swap the `retrain` stand-in for `LLM_CALL` (`provider`, `model`,
  `apiKey: "decrypted:..."`, `system` describing your eval policy, `prompt` built from the metrics and
  `${payload.notes}`) to summarise the eval report for the reviewer. `LLM_CALL` returns text, so add a
  `MAPPER` after it that puts the text in `summary` and passes `name`, `version`, the metrics,
  `maxLatencyMs`, `attempt` and `notes` along. Threshold checks and the reviewer stay as they are, so a
  model cannot be promoted on its own.
- **Promote and notify:** swap `promote` for `HTTP_REQUEST` (`method: POST`, `url` of your model
  registry, `body` from the payload) and `notify-*` for `NOTIFY_TERMINAL` (Slack webhook) fed by a
  MAPPER that sets `text`. The files go to `/tmp/yukta-examples/model-promotion`; change `baseDir`
  (it is not variable-resolved).
- **Approver:** the reviewer can answer from the web UI, the CLI or MCP (`signal_node`, `list_pending_approvals`).

## Trigger

`candidate.json` is a ready payload.

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-12-model-promotion","workflowId":"review","payload":{"name":"churn-model","version":"v7","accuracy":0.93,"latencyMs":120,"biasGap":0.03}}'

# what is waiting for the reviewer (the execution id is the round's, not the parent's)
curl localhost:9858/api/sessions/example-12-model-promotion/approvals

# answer: approve | retrain (with notes) | reject
curl -X POST "localhost:9858/api/executions/<executionId>/nodes/reviewer/signal?name=promote&decision=retrain&data=rebalance%20the%20training%20set"
```

## Run

Needs no `PROCESS_EXECUTOR` allow-list: every stand-in is a MAPPER or FILE_WRITE.

```bash
./examples/12-model-promotion/run.sh
```

The script plays the reviewer. It applies the session and runs seven paths: approve at once, low
accuracy caught before the reviewer, retrain then approve (notes carried), three rounds of retrains
(stop), reject, too slow (no reviewer needed), and an invalid candidate.

## Expected result

The last line is `PASS 12-model-promotion: 7 paths`. Approved models appear as `registry/<name>-<version>.json` and
`PROMOTED: ...` in `outbox.log` under `/tmp/yukta-examples/model-promotion`; stopped ones leave a
`NOT PROMOTED: ...` line and no registry file.
