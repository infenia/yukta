# Yukta examples

Ready-to-run workflow configurations for common use cases, with scripts that apply each
configuration, start it, and check the result. Nothing here needs code changes; each
example is a session JSON plus a small bash script.

## Prerequisites

- A running Yukta server: `./gradlew bootRun`, or the native binary, or the Docker image
  (set `YUKTA_SERVER_ADDRESS=0.0.0.0` in a container). Default address: `http://localhost:9858`.
- `curl` and `jq`.
- Examples 01, 02, 03 and 08 run operating-system commands, which Yukta refuses by default. Allow only what they need, for
  example `YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo` (property `yukta.process.allowed-commands`); each README names its set.
  Example 08 also needs `python3` for its local pager stand-in. Without an allowlist those examples exit `3` (SKIP).
- Optional: an OpenAI-compatible LLM endpoint for example 09.

Sessions are kept in memory. Re-run an example after restarting the server; `run.sh` applies
its session again each time.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `YUKTA_URL` | `http://localhost:9858` | Server base URL |
| `YUKTA_API_KEY` | unset | Sent as `X-API-Key` when the server sets `yukta.api-key` |
| `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY` | Ollama defaults | Used by example 09 only |

## Examples

Pick the use case closest to yours, run it, then change it. The use-case examples are meant to be copied: each README has a
"Make it yours" section that says which nodes to change and which stand-in node to swap for `LLM_CALL`. They are deterministic
by default (fixed rules, no model, no internet), so they run offline and in `run-all.sh`.

### Use-case templates

| # | Domain | Pain point | Key nodes |
|---|---|---|---|
| [02](02-agent-verification/) | AI-assisted development | An agent skips tests or verification and reports "done" | `LOOP_PREDICATE`, `SUB_WORKFLOW`, `PATCH_GUARD`, `BRANCH`, `OUTCOME_TERMINAL` |
| [03](03-release-approval/) | Release / DevOps | Production deploy with a human approval, a smoke check and a rollback | `WAIT_FOR_SIGNAL`, `PROCESS_EXECUTOR`, `BRANCH`, `NOTIFY_TERMINAL` |
| [04](04-loan-approval/) | Finance | Auto-approve clear cases, send borderline ones to an underwriter | `VALIDATE`, `MAPPER`, `BRANCH`, `WAIT_FOR_SIGNAL`, `CONTENT_FILTER` |
| [05](05-returns-fraud/) | E-commerce | Refund low-risk returns, hold high-risk ones for review | `VALIDATE`, `MAPPER`, `BRANCH`, `WAIT_FOR_SIGNAL`, `FILE_WRITE` |
| [06](06-support-triage/) | Customer support | Answer routine tickets, escalate disputes, skip duplicates | `KV_STORE`, `BRANCH`, `SUB_WORKFLOW`, `RECIPIENT_LIST` |
| [07](07-nightly-data-pipeline/) | Data and analytics | Nightly load with validation, dedupe, quarantine and a bad-row limit | `SCHEDULE_TRIGGER`, `FILE_READ`, `SPLITTER`, `VALIDATE`, `AGGREGATE` |
| [08](08-alert-triage/) | Operations / SRE | Try to fix an alert automatically, page a person if it persists | `LOOP_PREDICATE`, `PROCESS_EXECUTOR`, `NOTIFY_TERMINAL`, `WAIT_FOR_SIGNAL` |
| [12](12-content-review/) | Content / marketing | A draft is checked, edited by a person, revised up to a limit, then published | `SUB_WORKFLOW`, `WAIT_FOR_SIGNAL`, `BRANCH`, `FILE_WRITE` |
| [13](13-multi-agent/) | Multi-agent | Researcher, writer and independent verifier, with a retry cap and shared state | `SUB_WORKFLOW`, `LOOP_PREDICATE`, `KV_STORE`, `BRANCH` |
| [14](14-recruiting/) | HR / recruiting | Score a batch of applicants; a recruiter decides without seeing protected fields | `SPLITTER`, `CONTENT_FILTER`, `WAIT_FOR_SIGNAL`, `AGGREGATE` |

### Building blocks and reference

| # | Use case | Graph | Trigger |
|---|---|---|---|
| [01](01-hello-world/) | Smallest workflow, end-to-end check | CONSTANT_SOURCE → PROCESS_EXECUTOR → terminal | REST |
| [09](09-llm-chat-agent/) | Chat agent with per-user memory (needs an LLM endpoint) | SESSION_MEMORY → LLM_CALL → SESSION_MEMORY | REST |
| [10](10-mcp-agent/) | AI agent drives Yukta over MCP | MCP `create_session` / `start_workflow` | MCP |
| [11](11-dogfood/) | How Yukta develops itself: this repo's `gradle check` as a staged, self-healing workflow (failed stages are repaired by `spotlessApply` and headless Claude Code, then rechecked) | CONSTANT_SOURCE → PROCESS_EXECUTOR stages with `routeByExitCode` repair chains → terminal | REST / CLI |

Approval steps (`WAIT_FOR_SIGNAL`) are answered by each `run.sh` itself, which polls `GET /api/sessions/{id}/approvals` and sends the
signal. In real use a person or an agent sends it from the UI, the CLI, REST or MCP.

Example 10 note: MCP output validation rejects executions that are still running, so that
example waits over REST and reads the result through MCP only after the run finishes.

## How workflows are triggered

Yukta has no webhook trigger. The examples are started by hand; a run starts from one of these:

- **REST:** `POST /api/workflow/start` with `{"sessionId", "workflowId", "payload"?}`. Returns `202` and `data.executionId`.
- **MCP:** the `start_workflow` tool at `http://localhost:9858/mcp` (see example 10).
- **CLI:** `cli/build/yukta-cli-linux-amd64 workflow start <session> <workflow>`.
- **UI:** the web console.

To run on a timer, use the built-in `SCHEDULE_TRIGGER` (interval or cron) as the workflow's trigger, or call the REST endpoint from cron or CI.

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
