# Production release with approval and automatic rollback

## The pain point

A release that goes to production without a person saying yes is risky, and a release that
needs a person to watch it afterwards is slow. You want the checks to run by themselves,
one clear approval before production, and a rollback that starts the moment the smoke check fails.

## The graph

```
trigger ─► build ─► test ─► staging ─► approve (WAIT_FOR_SIGNAL) ─► after-approval ─go─► deploy ─► smoke ─► live ──────┐
             │        │        │            │                            └expired─► expired ─────────────────────┤
             └────────┴────────┴─failure─► blocked ──────────────────────────────────────────────────────────────┤
                                            └ reject ─► rejected (OUTCOME FAILED)                                  │
                                  deploy ─failure─┐                                                                │
                                  smoke ──failure─┴─► rollback ─► rolled-back ─────────────────────────────────────┤
                                                                                                                    ▼
                                         finish (BRANCH on result) ◄── outbox (FILE_WRITE notices.log) ◄───────────┘
                                           ├ ok ───────► released     (OUTCOME SUCCESS)
                                           ├ failed ───► not-released (OUTCOME FAILED)
                                           └ escalate ─► needs-owner  (OUTCOME ESCALATE)
```

## What each node does

| Node | Plugin | Job |
|---|---|---|
| `trigger` | `API_TRIGGER` | Start payload `{"version": "..."}` |
| `build`, `test`, `staging` | `PROCESS_EXECUTOR` | Run `release.sh build/test/staging VERSION`; exit code picks `success` or `failure` |
| `blocked` | `MAPPER` | Failed before approval: builds the notice from the command output |
| `approve` | `WAIT_FOR_SIGNAL` | Waits 15 minutes for signal `approve-release`; on timeout it continues as `timeout` |
| `after-approval` | `BRANCH` | `timeout` goes to `expired`, any other decision goes to `deploy` |
| `deploy`, `smoke` | `PROCESS_EXECUTOR` | Deploy to production, then check it; a failure of either goes to `rollback` |
| `rollback` | `PROCESS_EXECUTOR` | Restores the previous version |
| `live`, `expired`, `rolled-back` | `MAPPER` | Set `result` and the notice `text` for each path |
| `outbox` | `FILE_WRITE` | Appends the notice to `/tmp/yukta-example-release-approval/notices.log` (stand-in for chat or email) |
| `finish` | `BRANCH` | Maps `result` to one of three outcome terminals |
| `released`, `not-released`, `needs-owner`, `rejected` | `OUTCOME_TERMINAL` | Record how the run ended |

`reject` is special in `WAIT_FOR_SIGNAL`: it fails the node, and `engine.onError: ROUTE` sends the
message to the `error` port, which leads straight to `rejected`. Only a terminal can follow the
`error` port directly in this graph, so a rejection is not announced through `outbox`.

## Make it yours

- Commands: `release.sh` is a stand-in. Replace each `command` with your real one, for example `["./gradlew","build"]` for `build`, `["kubectl","rollout","status","deploy/app"]` for `staging`, and `["curl","-fsS","https://app.example.com/health"]` for `smoke`. The server must allow those executables (`yukta.process.allowed-commands`).
- Approval window and signal name: `timeoutSeconds` and `signal` on `approve`.
- Notices: replace `outbox` with `HTTP_REQUEST` to your chat webhook, or add a `NOTIFY_TERMINAL` as a branch.
- Several approvers: add a second `WAIT_FOR_SIGNAL` (for example `security-approve`) after `approve`.
- LLM: none needed. To have a model draft release notes for the approver, add an `LLM_CALL` before `approve` that reads the build output.

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"example-03-release-approval\",\"workflowId\":\"release\",\"payload\":$(cat payload.json)}"

curl localhost:9858/api/sessions/example-03-release-approval/approvals
curl -X POST "localhost:9858/api/executions/<executionId>/nodes/approve/signal?name=approve-release&decision=approve"
```

## How to run

Start the server with `yukta.process.allowed-commands=sh` (environment: `YUKTA_PROCESS_ALLOWED_COMMANDS=sh`). 
Replace the list with your real executables when you swap in your own commands.

```bash
./examples/03-release-approval/run.sh
```

`run.sh` installs `release.sh` into `/tmp/yukta-example-release-approval`, then runs six releases and acts as the approver:
approve, smoke check fails (rollback), build fails, tests fail, reject, and approval timeout (shortened to 2 seconds for the test).
It exits 3 (SKIP) when the server does not allow commands.

## Expected result

Ends with `PASS 03-release-approval`. Production runs `v1.5.0` at the end: the failed, blocked, rejected and expired releases never replaced it.
