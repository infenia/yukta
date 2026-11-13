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

### With Docker

The image carries BusyBox (`sh`, `echo`), so examples 01, 03 and 04 run in it. The examples keep their files in
`/tmp/yukta-examples` and read them back, so the container shares that folder with the machine running the scripts. Run
it as your own user (the image's default user cannot clean up files the scripts create, and needs a writable home):

```bash
mkdir -p /tmp/yukta-examples
docker run --rm -p 127.0.0.1:9858:9858 --user "$(id -u):$(id -g)" --workdir /tmp -e HOME=/tmp \
  -v /tmp/yukta-examples:/tmp/yukta-examples \
  -e YUKTA_API_KEYS_0_NAME=admin -e YUKTA_API_KEYS_0_KEY=<a-long-random-key> -e YUKTA_API_KEYS_0_ROLE=ADMIN \
  -e YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo \
  ghcr.io/infenia/yukta:<version>
YUKTA_API_KEY=<the-key> ./examples/run-all.sh
```

Two examples need something the image does not have: **02** needs `git` where the server runs (it skips with a message), and
**08** posts to a pager on this machine's `127.0.0.1:9877`, which a container cannot reach. Add `--network host` (Linux) in
place of `-p`, and set `SERVER_PORT` if 9858 is taken, to run 08; its pager also needs `python3` here. Example 09 needs an
LLM endpoint and `YUKTA_SECRET_LLM_API_KEY` set on the server (see its README).

Sessions are kept in memory. Re-run an example after restarting the server; `run.sh` applies
its session again each time.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `YUKTA_URL` | `http://localhost:9858` | Server base URL |
| `YUKTA_API_KEY` | unset | Sent as `X-API-Key` when the server sets `yukta.api-keys` |
| `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY` | Ollama defaults | Used by example 09 only |

## Examples

Pick the use case closest to yours, run it, then change it. The use-case examples are meant to be copied: each README has a
"Make it yours" section that says which nodes to change and which stand-in node to swap for `LLM_CALL`. They are deterministic
by default (fixed rules, no model, no internet), so they run offline and in `run-all.sh`.

### Use-case templates

| # | Domain | Pain point | Key nodes |
|---|---|---|---|
| [02](02-agent-verification/) | AI-assisted development | An agent skips tests or verification and reports "done" | `LOOP_PREDICATE`, `SUB_WORKFLOW`, `BRANCH`, `OUTCOME_TERMINAL` |
| [03](03-release-approval/) | Release / DevOps | Production deploy with a human approval, a smoke check and a rollback | `WAIT_FOR_SIGNAL`, `PROCESS_EXECUTOR`, `BRANCH`, `NOTIFY_TERMINAL` |
| [04](04-pr-risk-gate/) | Code review / app dev | Auto-merge low-risk PRs, block the worst, send borderline ones to a reviewer | `VALIDATE`, `MAPPER`, `BRANCH`, `WAIT_FOR_SIGNAL`, `CONTENT_FILTER` |
| [05](05-dependency-updates/) | Supply chain | Auto-merge patch bumps, hold majors and CVE-flagged ones for a maintainer | `VALIDATE`, `MAPPER`, `BRANCH`, `WAIT_FOR_SIGNAL`, `FILE_WRITE` |
| [06](06-crash-report-triage/) | Backend / observability | Link known crashes to their ticket, escalate new ones, skip duplicates | `KV_STORE`, `BRANCH`, `SUB_WORKFLOW`, `RECIPIENT_LIST` |
| [07](07-event-data-pipeline/) | Data engineering | Nightly event-log load with validation, dedupe, quarantine and a bad-row limit | `SCHEDULE_TRIGGER`, `FILE_READ`, `SPLITTER`, `VALIDATE`, `AGGREGATE` |
| [08](08-alert-triage/) | SRE / operations | Try to fix an alert automatically, page a person if it persists | `LOOP_PREDICATE`, `PROCESS_EXECUTOR`, `NOTIFY_TERMINAL`, `WAIT_FOR_SIGNAL` |
| [12](12-model-promotion/) | MLOps | A model candidate is eval-checked, reviewed, retrained up to a limit, then promoted | `SUB_WORKFLOW`, `WAIT_FOR_SIGNAL`, `BRANCH`, `FILE_WRITE` |
| [13](13-multi-agent/) | Multi-agent | Researcher, writer and independent verifier, with a retry cap and shared state | `SUB_WORKFLOW`, `LOOP_PREDICATE`, `KV_STORE`, `BRANCH` |
| [14](14-security-findings/) | AppSec | Score a batch of scanner findings; an engineer decides with secrets redacted | `SPLITTER`, `CONTENT_FILTER`, `WAIT_FOR_SIGNAL`, `AGGREGATE` |

### Building blocks and reference

| # | Use case | Graph | Trigger |
|---|---|---|---|
| [01](01-hello-world/) | Smallest workflow, end-to-end check | CONSTANT_SOURCE → PROCESS_EXECUTOR → terminal | REST |
| [09](09-llm-chat-agent/) | Chat agent with per-user memory (needs an LLM endpoint) | SESSION_MEMORY → LLM_CALL → SESSION_MEMORY | REST |
| [10](10-mcp-agent/) | AI agent drives Yukta over MCP | MCP `create_session` / `start_workflow` | MCP |
| [19](19-agent-session/) | Run an AI agent (Claude Code, Codex) with approvals through the input request system (needs an agent CLI; no `run.sh`) | API_TRIGGER → AGENT_SESSION → three outcomes | REST |
| [dogfood](dogfood/) | How Yukta develops itself: this repo's `gradle check` as a staged, self-healing workflow (failed stages are repaired by `spotlessApply` and headless Claude Code, then rechecked) | CONSTANT_SOURCE → PROCESS_EXECUTOR stages with `routeByExitCode` repair chains → terminal | REST / CLI |

### Supervisors

A supervisor watches runs and steers them: it vetoes commands, advises on input requests, or answers low risk ones. Three use-case
examples carry one; their `run.sh` creates it (`PUT /api/supervisors/<id>`, an upsert) before it applies the session with the supervisor
declared, and then proves it acted. `session.json` itself stays a plain template (a declared supervisor must exist when a session is saved).

| # | Supervisor | What it does |
|---|---|---|
| [03](03-release-approval/) | `rule-supervisor` `deny` | Refuses a `KILL` stop of a release (`403`) |
| [04](04-pr-risk-gate/) | `rule-supervisor` `escalate`, section `reviewer` | Adds advice to the reviewer's request |
| [08](08-alert-triage/) | `rule-supervisor` `escalate`, section `wait-ack` | Adds advice to the on-call's acknowledgement |
| [16](16-supervisor-profiles/) | `rule-supervisor` `answer` + `escalate` | Approves low risk staging input requests, escalates the rest |
| [17](17-supervisor-workflow/) | `workflow` | A workflow decides for a session, and a section supervisor hands up to it |

A `WAIT_FOR_SIGNAL` approval has risk `HIGH`, so a supervisor can only advise on it; a supervisor answers requests of risk `LOW` (or `MEDIUM`) that come
from `ASK_INPUT` or an agent. Example 16 and its docs page "Input requests" show that. Create 16 with `POST /api/supervisors` (admin key) and attach it
to a session with `PUT /api/supervisors/{id}/assignments` (`[{"target":"session:<id>"}]`). A test loads it, so it stays valid.

Approval steps (`WAIT_FOR_SIGNAL`) are answered by each `run.sh` itself, which polls `GET /api/sessions/{id}/inputs?state=OPEN,ESCALATED` and answers the
request with `POST /api/executions/{e}/inputs/{requestId}/answer`. In real use a person answers it (an agent may too, over MCP; a supervisor cannot, see above) from the UI,
the CLI (`yukta inputs`), REST or MCP.

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
  lib/common.sh        shared helpers: api, apply_session, apply_supervisor, run_workflow
  run-all.sh           runs every example and prints a summary
  NN-<name>/
    session.json       session and workflow definitions
    supervisor.json    the supervisor of the example, where it has one (03, 04, 08)
    payload.json       request payload, where the trigger takes one
    run.sh             apply, start, wait, assert
    README.md          use case, graph, trigger, expected result
```

Each `session.json` is also a valid body for `POST /api/sessions`.
