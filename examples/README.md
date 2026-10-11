# Yukta examples

Ready-to-run workflow configurations for common use cases, with scripts that apply each
configuration, start it, and check the result. Nothing here needs code changes; each
example is a session JSON plus a small bash script.

## Prerequisites

- A running Yukta server: `./gradlew bootRun`, or the native binary, or the Docker image
  (set `YUKTA_SERVER_ADDRESS=0.0.0.0` in a container). Default address: `http://localhost:8080`.
- `curl` and `jq`.
- Optional: an OpenAI-compatible LLM endpoint for example 09.

Sessions are kept in memory. Re-run an example after restarting the server; `run.sh` applies
its session again each time.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `YUKTA_URL` | `http://localhost:8080` | Server base URL |
| `YUKTA_API_KEY` | unset | Sent as `X-API-Key` when the server sets `yukta.api-key` |
| `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY` | Ollama defaults | Used by example 09 only |

## Examples

| # | Use case | Graph | Trigger |
|---|---|---|---|
| [01](01-hello-world/) | Smallest workflow, end-to-end check | ONE_SHOT → PROCESS_EXECUTOR → terminal | REST |
| [02](02-api-payload/) | External system passes data over REST | api-trigger → MAPPER → terminal | REST + payload |
| [03](03-ci-pipeline/) | CI gate: pass/fail by exit code | PROCESS_EXECUTOR `routeByExitCode` → two reports | REST |
| [04](04-quality-gate/) | AI-agent quality gate on metrics | api-trigger → BRANCH → two reports | REST + payload |
| [05](05-data-pipeline/) | Batch ETL: split, filter, map | CONSTANT_SOURCE → SPLITTER → FILTER → MAPPER | REST |
| [06](06-http-health-check/) | Monitoring with an alert path | HTTP_REQUEST `routeByStatus` → two reports | REST |
| [07](07-fan-out-notify/) | One event to several channels | RECIPIENT_LIST → three notifications | REST |
| [08](08-retry-until-ready/) | Retry a check until ready, bounded | LOOP_PREDICATE | REST |
| [09](09-llm-chat-agent/) | Chat agent with per-user memory | SESSION_MEMORY → LLM_CALL → SESSION_MEMORY | REST |
| [10](10-mcp-agent/) | AI agent drives Yukta over MCP | MCP `create_session` / `start_workflow` | MCP |

Example 10 note: MCP output validation rejects executions that are still running, so that
example waits over REST and reads the result through MCP only after the run finishes.

## How workflows are triggered

Yukta has no webhook or cron trigger. A run starts from one of these:

- **REST:** `POST /api/workflow/start` with `{"sessionId", "workflowId", "payload"?}`. Returns `202` and `data.executionId`.
- **MCP:** the `start_workflow` tool at `http://localhost:8080/mcp` (see example 10).
- **CLI:** `cli/build/yukta-linux-amd64 workflow start <session> <workflow>`.
- **UI:** the web console.

Schedule it yourself with cron, a CI job or any scheduler that can make the REST call.

## Running

```bash
./examples/01-hello-world/run.sh       # one example
./examples/run-all.sh                  # all of them, with a summary
```

Exit codes: `0` PASS, `3` SKIP (a needed plugin or LLM endpoint is missing), anything else FAIL.

## Layout

```
examples/
  lib/common.sh        shared helpers: api, apply_session, run_workflow
  run-all.sh           runs every example and prints a summary
  NN-<name>/
    session.json       session and workflow definitions
    payload.json       request payload, where the trigger takes one
    run.sh             apply, start, wait, assert
    README.md          use case, graph, trigger, expected result
```

Each `session.json` is also a valid body for `POST /api/sessions`.
