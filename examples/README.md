# Yukta examples

Ready-to-run workflow configurations for common use cases, with scripts that apply each
configuration, start it, and check the result. Nothing here needs code changes; each
example is a session JSON plus a small bash script.

## Categories

The examples are grouped by what they need on the machine that runs them.

| Category | Folder | Needs | Examples |
|---|---|---|---|
| 1. Standalone | [`1-standalone/`](1-standalone/) | Only the server. The process allowlist `sh,echo` is enough. | 01, 02, 03, 04, 05, 06, 07, 08, 09, 10, 17 |
| 2. Free dependencies | [`2-free-deps/`](2-free-deps/) | Free tools on the machine: `git`, `python3`, `go`, `uv`, `bun` or `node`. Some examples start a loopback stand-in for an outside service. | 11, 12, 13, 14, 18, 19 |
| 3. Paid tools | [`3-paid-tools/`](3-paid-tools/) | An AI agent CLI (Claude Code, Codex) or a hosted model. The examples here run against a fake harness unless you configure the real tool. | 15, 16 |

Example 15 is a walkthrough for now (it has no `run.sh`). Its README says how to run it with a real agent CLI.

## Order and categories in the UI

The examples are the session templates of the web console (`GET /api/templates`). [`index.json`](index.json) decides how they are
shown: `order` lists the example ids from first to last (the blank session always leads), and `categories` gives each folder a
label and the sentence a card shows as a tooltip. To move an example, move its id in `order`; one that is not listed comes after
the listed ones, by id. The build copies the file into the server, so rebuild (`yk gradle :boot:bootJar`) to see a change.

## Prerequisites

- A running Yukta server: `./gradlew bootRun`, or the native binary, or the Docker image
  (set `YUKTA_SERVER_ADDRESS=0.0.0.0` in a container). Default address: `http://localhost:9858`.
- `curl` and `jq`.
- Plugins that touch the outside (`PROCESS_EXECUTOR`, `FILE_*`, `HTTP_REQUEST`, `NOTIFY_TERMINAL`, `SESSION_MEMORY`,
  `LLM_CALL`, ...) are off until named. Start the server with `YUKTA_PLUGINS_ENABLED=all` (or list the plugins) or the examples
  that use them exit `3` (SKIP) or fail; `GET /api/plugins` shows what is enabled.
- Examples 02, 11 and 12 run operating-system commands, which Yukta refuses by default. Allow only what they need, for
  example `YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo` (property `yukta.process.allowed-commands`); each README names its set.
  Example 12 also needs `python3` for its local pager stand-in. Without an allowlist those examples exit `3` (SKIP).

### With Docker

The image carries BusyBox (`sh`, `echo`), so examples 01, 02 and 03 run in it. The examples keep their files in
`/tmp/yukta-examples` and read them back, so the container shares that folder with the machine running the scripts. Run
it as your own user (the image's default user cannot clean up files the scripts create, and needs a writable home):

```bash
mkdir -p /tmp/yukta-examples
docker run --rm -p 127.0.0.1:9858:9858 --user "$(id -u):$(id -g)" --workdir /tmp -e HOME=/tmp \
  -v /tmp/yukta-examples:/tmp/yukta-examples \
  -e YUKTA_API_KEYS_0_NAME=admin -e YUKTA_API_KEYS_0_KEY=<a-long-random-key> -e YUKTA_API_KEYS_0_ROLE=ADMIN \
  -e YUKTA_PLUGINS_ENABLED=all -e YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo \
  ghcr.io/infenia/yukta:<version>
YUKTA_API_KEY=<the-key> ./examples/run-all.sh 1
```

Two examples need something the image does not have: **02** needs `git` where the server runs (it skips with a message), and
**08** posts to a pager on this machine's `127.0.0.1:9877`, which a container cannot reach. Add `--network host` (Linux) in
place of `-p`, and set `SERVER_PORT` if 9858 is taken, to run 08; its pager also needs `python3` here.

To check all of it in one command, as often as you like, `release/examples-verify.sh jar|native|docker [--no-ui]` starts the
server in that form on a free port, runs every example, drives the UI (templates, a session from each, runs) and stops the server.
It needs the artifact built first (`yk gradle :boot:bootJar`, `release/build-server.sh`, `release/build-image.sh`; `YUKTA_IMAGE`
names the image) and, for the UI half, `ui/node_modules` and a Playwright Chromium.

The same examples from the browser: `release/ui-smoke.sh <url>` (Playwright against the real server, `ui/e2e/real/`) creates a
session from each template and runs examples through the UI.

Sessions are kept in memory. Re-run an example after restarting the server; `run.sh` applies
its session again each time.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `YUKTA_URL` | `http://localhost:9858` | Server base URL |
| `YUKTA_API_KEY` | unset | Sent as `X-API-Key` when the server sets `yukta.api-keys` |
| `EXAMPLES_SKIP` | unset | Names of examples `run-all.sh` reports as SKIP without running |
| `EXAMPLES_STRICT` | `false` | `true` makes a SKIP count as a failure (the nightly run uses it) |

## Examples

Pick the use case closest to yours, run it, then change it. The use-case examples are meant to be copied: each README has a
"Make it yours" section that says which nodes to change and which stand-in node to swap for `LLM_CALL`. They are deterministic
by default (fixed rules, no model, no internet), so they run offline and in `run-all.sh`.

### Use-case templates

| # | Category | Domain | Pain point | Key nodes |
|---|---|---|---|---|
| [11](2-free-deps/11-agent-verification/) | 2 | AI-assisted development | An agent skips tests or verification and reports "done" | `LOOP_PREDICATE`, `SUB_WORKFLOW`, `BRANCH`, `OUTCOME_TERMINAL` |
| [18](2-free-deps/18-todo-run/) | 2 | App development | Check a project out and run it, so a person can open it | `PROCESS_EXECUTOR` (git, uv, bun), long-running, in parallel |
| [19](2-free-deps/19-todo-ci/) | 2 | CI | Lint, format and test a backend and then a frontend, stop at the first failure | `PROCESS_EXECUTOR` stages in a chain |
| [02](1-standalone/02-release-approval/) | 1 | Release / DevOps | Production deploy with a human approval, a smoke check and a rollback | `WAIT_FOR_SIGNAL`, `PROCESS_EXECUTOR`, `BRANCH`, `NOTIFY_TERMINAL` |
| [03](1-standalone/03-pr-risk-gate/) | 1 | Code review / app dev | Auto-merge low-risk PRs, block the worst, send borderline ones to a reviewer | `VALIDATE`, `MAPPER`, `BRANCH`, `WAIT_FOR_SIGNAL`, `CONTENT_FILTER` |
| [04](1-standalone/04-dependency-updates/) | 1 | Supply chain | Auto-merge patch bumps, hold majors and CVE-flagged ones for a maintainer | `VALIDATE`, `MAPPER`, `BRANCH`, `WAIT_FOR_SIGNAL`, `FILE_WRITE` |
| [12](2-free-deps/12-alert-triage/) | 2 | SRE / operations | Try to fix an alert automatically, page a person if it persists | `LOOP_PREDICATE`, `PROCESS_EXECUTOR`, `NOTIFY_TERMINAL`, `WAIT_FOR_SIGNAL` |
| [05](1-standalone/05-model-promotion/) | 1 | MLOps | A model candidate is eval-checked, reviewed, retrained up to a limit, then promoted | `SUB_WORKFLOW`, `WAIT_FOR_SIGNAL`, `BRANCH`, `FILE_WRITE` |
| [06](1-standalone/06-security-findings/) | 1 | AppSec | Score a batch of scanner findings; an engineer decides with secrets redacted | `SPLITTER`, `CONTENT_FILTER`, `WAIT_FOR_SIGNAL`, `AGGREGATE` |

### Building blocks and reference

| # | Category | Use case | Graph | Trigger |
|---|---|---|---|---|
| [01](1-standalone/01-hello-world/) | 1 | Smallest workflow, end-to-end check | CONSTANT_SOURCE → PROCESS_EXECUTOR → terminal | REST |
| [13](2-free-deps/13-mcp-agent/) | 2 | AI agent drives Yukta over MCP | MCP `create_session` / `start_workflow` | MCP |
| [07](1-standalone/07-input-pull/) | 1 | An outside responder pulls and answers input requests | no session; `GET /api/inputs` | REST |
| [15](3-paid-tools/15-agent-session/) | 3 | Run an AI agent (Claude Code, Codex) with approvals through the input request system (needs an agent CLI; walkthrough, no `run.sh`) | API_TRIGGER → AGENT_SESSION → three outcomes | REST |
| [10](1-standalone/10-inbox-notification/) | 1 | A workflow raises a notification in the inbox | CONSTANT_SOURCE → PROCESS_EXECUTOR → NOTIFY_TERMINAL (`target: inbox`) | REST |
| [17](1-standalone/17-ask-random-question/) | 1 | Ask yes or no; on yes ask a random question and print question and answer | CONSTANT_SOURCE → ASK_INPUT → BRANCH → PROCESS_EXECUTOR → MAPPER → ASK_INPUT → PROCESS_EXECUTOR | REST |
| [16](3-paid-tools/16-agent-console/) | 3 | Two scripted agents in the agent console, against a fake harness | API_TRIGGER → AGENT_SESSION → OUTCOME_TERMINAL | REST |
| [dogfood](https://github.com/infenia/yukta-dogfood) | 3 | How Yukta develops itself: this repo's `gradle check` (now its own repository) as a staged, self-healing workflow (failed stages are repaired by `spotlessApply` and headless Claude Code, then rechecked) | CONSTANT_SOURCE → PROCESS_EXECUTOR stages with `routeByExitCode` repair chains → terminal | REST / CLI |

### Supervisors

A supervisor watches runs and steers them: it vetoes commands, advises on input requests, or answers low risk ones. Three use-case
examples carry one; their `run.sh` creates it (`PUT /api/supervisors/<id>`, an upsert) before it applies the session with the supervisor
declared, and then proves it acted. `session.json` itself stays a plain template (a declared supervisor must exist when a session is saved).

| # | Supervisor | What it does |
|---|---|---|
| [02](1-standalone/02-release-approval/) | `rule-supervisor` `deny` | Refuses a `KILL` stop of a release (`403`) |
| [03](1-standalone/03-pr-risk-gate/) | `rule-supervisor` `escalate`, section `reviewer` | Adds advice to the reviewer's request |
| [12](2-free-deps/12-alert-triage/) | `rule-supervisor` `escalate`, section `wait-ack` | Adds advice to the on-call's acknowledgement |
| [08](1-standalone/08-supervisor-profiles/) | `rule-supervisor` `answer` + `escalate` | Approves low risk staging input requests, escalates the rest |
| [09](1-standalone/09-supervisor-workflow/) | `workflow` | A workflow decides for a session, and a section supervisor hands up to it |

A `WAIT_FOR_SIGNAL` approval has risk `HIGH`, so a supervisor can only advise on it; a supervisor answers requests of risk `LOW` (or `MEDIUM`) that come
from `ASK_INPUT` or an agent. Example 08 and its docs page "Input requests" show that. Create 08 with `POST /api/supervisors` (admin key) and attach it
to a session with `PUT /api/supervisors/{id}/assignments` (`[{"target":"session:<id>"}]`). A test loads it, so it stays valid.

Approval steps (`WAIT_FOR_SIGNAL`) are answered by each `run.sh` itself, which polls `GET /api/sessions/{id}/inputs?state=OPEN,ESCALATED` and answers the
request with `POST /api/executions/{e}/inputs/{requestId}/answer`. In real use a person answers it (an agent may too, over MCP; a supervisor cannot, see above) from the UI,
the CLI (`yukta inputs`), REST or MCP.

Example 13 note: MCP output validation rejects executions that are still running, so that
example waits over REST and reads the result through MCP only after the run finishes.

## How workflows are triggered

Yukta has no webhook trigger. The examples are started by hand; a run starts from one of these:

- **REST:** `POST /api/workflow/start` with `{"sessionId", "workflowId", "payload"?}`. Returns `202` and `data.executionId`.
- **MCP:** the `start_workflow` tool at `http://localhost:9858/mcp` (see example 13).
- **CLI:** `cli/build/yukta-cli-linux-amd64 workflow start <session> <workflow>`.
- **UI:** the web console.

To run on a timer, use the built-in `SCHEDULE_TRIGGER` (interval or cron) as the workflow's trigger, or call the REST endpoint from cron or CI.

## Running

```bash
./examples/1-standalone/01-hello-world/run.sh       # one example
./examples/run-all.sh                  # all categories, with a summary
./examples/run-all.sh 1                # one category: 1, 2, 3 or all
```

Exit codes: `0` PASS, `3` SKIP (a needed plugin or LLM endpoint is missing), anything else FAIL.

## Layout

```
examples/
  lib/common.sh        shared helpers: api, apply_session, apply_supervisor, run_workflow
  run-all.sh           runs the examples of one category, or all, and prints a summary
  1-standalone/        category 1: no external dependency
  2-free-deps/         category 2: free tools or loopback stand-ins
  3-paid-tools/        category 3: AI agent CLIs and hosted models
    NN-<name>/
      session.json     session and workflow definitions
      supervisor.json  the supervisor of the example, where it has one (02, 03, 12)
      payload.json     request payload, where the trigger takes one
      run.sh           apply, start, wait, assert
      README.md        use case, graph, trigger, expected result
```
