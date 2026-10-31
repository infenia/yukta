# Dependency update triage

## The pain point

Dependabot-style bump PRs pile up. Patch and minor updates are safe and boring, yet they wait for a
human. Major bumps and CVE fixes need a maintainer's eye. You want the small ones merged at once, the
risky ones in a review queue, and a notification that never leaks your registry token or scoring rules.

## The graph

```
bump-request ─► validate ──invalid──► mark-invalid ───────────────────────────────┐
                    │                                                             │
                    ▼                                                             │
                  risk ─► route ──low──► auto-approve ─┐                          │
                             │                          ├► changelog-line ─► changelog ─┤
                             └─high─► review-queue      │                          │
                                        ├ answer ─► after-review                   │
                                        │    ├ approved ─► reviewer-approve ─┘     │
                                        │    ├ denied ───► reviewer-deny ──────────┤
                                        │    └ timed-out ► review-timeout ─────────┤
                                        └ reject ─► security-escalation (OUTCOME ESCALATE)
                                                                                   │
                redact ◄───────────────────────────────────────────────────────────┘
                   └─► compose ─► notify-maintainer (FILE_WRITE outbox) ─► end
```

## What each node does

| Node | Plugin | Job |
|---|---|---|
| `bump-request` | `API_TRIGGER` | Takes the bump request as the start payload |
| `validate` | `VALIDATE` | Checks required fields and types; bad input leaves on `invalid` |
| `risk` | `MAPPER` | Adds `riskScore`: major (+40) or minor (+10), `hasCve` (+40), failing tests (+30), more than 10 dependents (+15) |
| `route` | `BRANCH` | Score under 30 is `low` (auto-merge), otherwise `high` |
| `auto-approve` | `MAPPER` | Marks the bump `MERGE_APPROVED` by rules |
| `review-queue` | `WAIT_FOR_SIGNAL` | Holds risky bumps until signal `review` arrives (24 h, then it continues as `timeout`) |
| `after-review` | `BRANCH` | Reads `metadata['signal.decision']`: `timeout`, `deny`, or anything else (approve) |
| `reviewer-approve`, `reviewer-deny`, `review-timeout` | `MAPPER` | Record the maintainer's result, or say the review is taking longer |
| `security-escalation` | `OUTCOME_TERMINAL` | A `reject` signal hands the update to the security team and ends the run as `ESCALATE`; no merge, no notification |
| `changelog-line`, `changelog` | `MAPPER`, `FILE_WRITE` | Write one line to `/tmp/yukta-example-dependency-updates/changelog.log` (stand-in for the merge and changelog commit) |
| `redact` | `CONTENT_FILTER` | Removes `registryToken`, `riskScore`, `decidedBy` and the internal counters |
| `compose`, `notify-maintainer` | `MAPPER`, `FILE_WRITE` | Append the message to `outbox.log` (stand-in for Slack or email) |

`reject` is special in `WAIT_FOR_SIGNAL`: it fails the node and `engine.onError: ROUTE` sends the message to the `error` port.
Use `decision=deny` to decline an update and still notify.

## Make it yours

- Risk rules and thresholds: edit the `riskScore` expression in `risk` and the cases in `route`.
- Fields to hide: `paths` in `redact`.
- Merging: replace `changelog` with an `HTTP_REQUEST` to the GitHub API (`method: PUT` on the merge endpoint, a `body` built in `changelog-line`, `failureMode: CONTINUE` with `routeByStatus` so a failed merge is routed, not lost).
- Message: `note` fields in the decision `MAPPER` nodes and the `compose` expression; replace `notify-maintainer` with `HTTP_REQUEST` or `NOTIFY_TERMINAL`.
- LLM: the risk score is fixed rules. Swap in `LLM_CALL` as a changelog-risk summariser before `route` or `review-queue`: it reads the release notes and gives the maintainer a short list of breaking changes. `ANOMALY` (needs the decision module and a model) can add a second risk signal to `riskScore`.

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"example-05-dependency-updates\",\"workflowId\":\"updates\",\"payload\":$(cat major-bump.json)}"

curl localhost:9858/api/sessions/example-05-dependency-updates/approvals
curl -X POST "localhost:9858/api/executions/<executionId>/nodes/review-queue/signal?name=review&decision=approve"
```

## Run

```bash
./examples/05-dependency-updates/run.sh
```

`run.sh` sends `patch-bump.json`, `invalid.json` and `major-bump.json` (four times: approve, deny, reject, timeout) and acts as the maintainer.

## Expected result

Ends with `PASS 05-dependency-updates`: two lines in `changelog.log` (the patch bump and the approved major bump), and notifications without registry tokens or scores.
