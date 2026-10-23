# Competitive analysis

Internal R&D note (not published to the docs site): the tools Yukta competes with, their pros and cons,
which of their features Yukta could take, what their users ask for most, how heavy they are to run, and
which gaps are now tracked as issues.

Work on this note is tracked in its Epic issue on the Yukta Roadmap project (search for
`competitive analysis`).

Researched 2026-10-23 against `main@85848d8`. **How sure each part is:**

- Yukta's own capabilities were checked by reading the code and `docs/` on that commit.
- Competitor features come from their docs and release posts. Their user requests come from each
  project's GitHub tracker sorted by 👍 reactions (n8n's real backlog is on its community forum,
  so the GitHub list for n8n shows mostly bugs).
- Resource and throughput figures are **published figures, not measured here.** Most are from
  vendors or hosting blogs, and they were taken on different hardware with different workloads.
  Use them to get a rough idea of size, not to rank the tools. The public comparison page
  (`docs/concepts/compared-with-other-tools.mdx`) stays the user-facing version; this note goes
  deeper and is allowed to be blunt.

## Summary

- **Yukta's niche holds up.** No competitor combines all four of: workflows as JSON data, messages
  streamed between nodes with back-pressure, a single small server (about 150 MB idle, 0.35 s
  start-up native), and MCP control by AI agents. Kestra (YAML, JVM) and Conductor (JSON, JVM) are
  the closest in shape, and both need a database and several GB of RAM.
- **Where Yukta is weaker:** durability by default (Temporal, Restate, Hatchet, Inngest), number of
  connectors (n8n, Kestra, Airflow), data-engineering features such as assets, lineage and backfill
  (Dagster, Airflow, Prefect), and event-source triggers (Kestra, Node-RED, Conductor).
- **Most-requested features across competitors**, and where Yukta stands:
  - Already in Yukta: retry by error type, run priority, a concurrency cap per workflow, versions
    with diff and rollback, approvals, dark mode, retry or restart from a node.
  - Missing in Yukta: concurrency keys, debounce and throttle; masking secrets in logs; tests at
    workflow level; triggers fired by another workflow or by an external event; an OTLP exporter;
    GitOps sync; a Gantt view; replaying a run with an edited input.
- **New issues filed:** 12, #849-#860, grouped under epic #848 (see [Issues](#issues)). Gaps that existing
  issues already cover were not filed again. They are mapped in [Already tracked](#already-tracked).

## Landscape

| Category | Tools | How they overlap with Yukta |
|---|---|---|
| Durable execution (code-first) | Temporal, Restate, DBOS, Inngest, Hatchet, Trigger.dev | Long-running, fault-tolerant business logic; Yukta's Plus edition checkpoints compete here |
| Data and ML orchestration | Airflow, Prefect, Dagster | Scheduled pipelines in Python; Yukta's data-pipeline examples overlap |
| Declarative orchestration | Kestra, Conductor OSS, AWS Step Functions, Argo Workflows | Closest in model: a workflow is data (YAML or JSON) |
| Low-code automation | n8n, Node-RED, Windmill, Activepieces | Visual builders and many connectors; Yukta has a form-based builder |
| BPMN and human tasks | Camunda 8 (Zeebe) | Approvals and human steps |
| AI agent graphs | LangGraph, Dify, Flowise | Model calls, tools, memory, human in the loop; overlaps Yukta's `LLM_CALL`, `MCP_*` and decision plugins |
| CI quality gates | GitHub Actions, Dagger | The dogfood use case (`yukta-check.sh`) |

## Tools in detail

Each entry lists pros and cons as they affect someone who is choosing between that tool and Yukta,
then any feature that is worth taking, and whether Yukta can take it.

### Temporal

Durable execution: workflows are code in an SDK (Go, Java, TypeScript, Python, .NET), and an
event-sourced history replays them after a crash. MIT licence. Runs as a cluster with a database
(Cassandra, PostgreSQL or MySQL, plus Elasticsearch for visibility), or as Temporal Cloud.

| Pros | Cons |
|---|---|
| Strongest durability guarantees in the field: workflows can run for months | Workflows must be deterministic code, so they are not data and are hard for agents or non-programmers to write |
| Mature SDKs, signals, queries, updates, Update-With-Start, Nexus for calls between teams | Heavy to run yourself: several services, a database and shard tuning |
| Worker Versioning (GA March 2026) for safe code changes | Versioning and patching are a known source of mistakes |
| Huge production base | No built-in graph UI for authoring; streaming between steps is not its model |

**Worth taking:**
- Reset or replay a run from an event with changed input → [replay with an edited input](#issues). Feasible: restart-from-node already replays recorded outputs.
- Different retry options for each failure type. Yukta already has this (`retryOn` and `noRetryOn` on `engine.retry`).
- A concurrency limit for each workflow type, which is Temporal's #7666. Yukta already has `max-concurrent-per-workflow`, so only keyed concurrency is missing → [issue](#issues).
- Priority task queues, Temporal's #1507 with 27 👍. Yukta already has `yukta.priority`.

### Restate and DBOS

Light durable execution. Restate is one Rust binary with its own log. DBOS is a library that keeps
state in PostgreSQL. Both journal each step and resume it after a crash.

| Pros | Cons |
|---|---|
| One binary (Restate) or no server at all (DBOS); far simpler than Temporal | Still code-first; no graph-as-data |
| Low latency; DBOS reports 43k workflows/s on a 96-vCPU PostgreSQL (vendor figure) | Smaller ecosystems; Restate's licence is BSL |
| | Little visual monitoring |

**Worth taking:** mainly a lesson for the Plus checkpoint design: a step journal that stays cheap
when stored on one database. This is covered by the performance plan (#435) and #569. No new issue.

### Apache Airflow

Python DAGs, a scheduler, workers, and a metadata database. Apache 2.0. Airflow 3 added asset-based
scheduling and a new UI.

| Pros | Cons |
|---|---|
| The standard for batch data pipelines; huge set of providers | Heavy: the Docker Compose guide asks for at least 4 GB and ideally 8 GB of RAM |
| Backfill, assets (datasets), pools, SLAs | Slow for many small tasks: Windmill's benchmark measured 56 s for 40 short tasks |
| Managed offerings (MWAA, Astronomer, Cloud Composer) | Batch only, no streaming; DAGs are Python |

**Most-wanted in its tracker**, with Yukta's position:

| Request | Yukta |
|---|---|
| Retry a whole TaskGroup | Restart from a node already re-runs that node and everything after it |
| DAGs shown in folders | Sessions group workflows; enough for now |
| Global Gantt chart | Missing → [run timeline issue](#issues) |
| Git sync | Missing → [GitOps sync issue](#issues) |
| A forced, time-based run when a dataset is not ready | Covered by the workflow-event trigger plus a schedule |
| A "skip the secret backend" option | Not applicable |

### Prefect

Python flows with decorators, a server or cloud service, and workers. Apache 2.0.

| Pros | Cons |
|---|---|
| Easy to start in Python; good UI; task caching | Python only; the server needs PostgreSQL to scale |
| Hybrid model: your code runs on your own infrastructure | Its tracker reports slow servers and timeouts under load ("Long load times", "Timeouts due to slow DB") |

**Most-wanted:**

| Request | Yukta |
|---|---|
| OpenTelemetry support | Yukta has Micrometer observations but no bundled exporter → [OTLP issue](#issues) |
| Retry flows that crashed | Covered by resume in the Plus edition |
| Flow-level caching | Covered by #468 (node result caching) |
| Database clean-up scripts | Covered by #617 |
| A 24-hour clock in the UI | Trivial; not filed |

### Dagster

Asset-based orchestration: you declare data assets and Dagster works out the runs. Apache 2.0.

| Pros | Cons |
|---|---|
| Lineage, partitions, asset checks, strong developer tooling | Python only, and the asset model has a learning curve |
| Built-in data-quality checks | Its tracker reports many PostgreSQL connections; RBAC is Dagster+ (paid) only |

**Most-wanted:**

| Request | Yukta |
|---|---|
| Authentication and RBAC in the open-source UI, the top issue | Yukta has roles on API keys; key expiry is #704 |
| OpenTelemetry traces and trace ids in log lines | Missing → [OTLP issue](#issues); trace ids in progress are #469 |
| A timeout for each op | Yukta already has `elementTimeoutMs` and `deadlineMs` |
| A trigger abstraction for push-style starts from external events | Missing → [event triggers issue](#issues) |
| A profiler or flame graph for jobs | Covered by the run timeline issue |

### Kestra

Declarative YAML flows, a JVM server (Java 21), PostgreSQL or MySQL, more than 1,000 plugins.
Apache 2.0 core, with an Enterprise edition. **This is the closest competitor in model and
technology stack.**

| Pros | Cons |
|---|---|
| Workflows as data (YAML), revisions, a UI editor with live docs | The standalone server needs about 4 GiB of RAM and 2 vCPU (official docs), plus a database |
| Realtime triggers (Kafka, AMQP, MQTT, files), flow triggers, webhooks | Script tasks need Docker-in-Docker |
| Backfill, flow-level `concurrency` (QUEUE, CANCEL or FAIL), SLAs, namespaces with quotas | Middling on short tasks: about 6 s for 40 tasks in Windmill's benchmark |
| AI Copilot that writes YAML | RBAC, audit and SSO are Enterprise only; no streaming between tasks |

**Worth taking (all feasible):**
- Flow triggers that start a flow when another flow ends → [issue](#issues).
- Realtime triggers from queues and files → [issue](#issues).
- Webhook triggers with a key → [issue](#issues).
- Concurrency limits keyed on input values → [issue](#issues). The QUEUE, CANCEL or FAIL choice
  already exists in Yukta as QUEUE or REJECT.
- Backfill is covered by #468 and #658.
- SLAs are partly there: Yukta has a run deadline, but nothing labels or alerts on a breach. Not
  filed; fold it into the event-trigger work.
- Top requests in Kestra's tracker: hide flows that only run as subflows, a dry run for bulk API
  calls, replaying only the missed runs during a backfill, and a Gantt view that is wrong for
  ForEach. These support the timeline and backfill items.

### Windmill

Code-first: scripts in about 20 languages become workflows and generated UIs. Rust server and workers
on PostgreSQL. AGPLv3, with an Enterprise edition.

| Pros | Cons |
|---|---|
| Fastest in its own benchmarks; dedicated workers | Licence is AGPL; SSO and audit are Enterprise only |
| Generated UIs and internal apps | Each worker runs one job at a time; docs suggest 1-2 GB per worker |
| Git sync, CLI, local development | Idle, server, worker and database take about 1 GB together (third-party measure) |

**Most-wanted:**

| Request | Yukta |
|---|---|
| Unit tests for flow steps and scripts, the top issue | Missing → [workflow test harness issue](#issues) |
| Non-root images | Check the Dockerfile; not filed |
| HashiCorp Vault secrets | Covered by #468 |
| Hide sensitive values from logs | Missing → [secret scrubbing issue](#issues) |
| Health endpoints on workers | Yukta has actuator health; the worker side is covered by the #709-#720 risks |
| Labels and tags on flows | Sessions already have `tags` |

### n8n

A visual low-code editor with about 400 integrations and an AI Agent node. Its licence is the
"Sustainable Use License" (fair-code, not OSI). Node.js, with SQLite or PostgreSQL, and Redis in
queue mode.

| Pros | Cons |
|---|---|
| Very easy for non-developers; huge template library | Not open source; queue mode and SSO are paid or Enterprise features |
| Official figure: 220 executions/s on a 4 GB c5a.large in single mode | Single mode is limited by Node's event loop; queue mode needs Redis and workers |
| Workflows can be marked "Available in MCP" | Workflows are JSON but hard to diff and review |

**Most-wanted** (GitHub, mostly AI-agent bugs):

| Request | Yukta |
|---|---|
| Tool errors returned to the agent instead of failing the workflow | Already possible: `MCP_TOOL_CALL failureMode: CONTINUE` with `routeByStatus` |
| The agent forgets which tools it used | Yukta's `SESSION_MEMORY` carries `actions` and tool turns (#465) |
| Ollama timeouts | Yukta has a `timeout` per call |

**Worth taking:** publishing workflows as MCP tools → [issue](#issues). It fits Yukta's MCP-first
pitch better than it fits n8n.

### Node-RED

Flow-based, event-driven, with a browser editor. Apache 2.0. Node.js.

| Pros | Cons |
|---|---|
| Very light: about 80 MB of RAM, runs on a Raspberry Pi | Not built for orchestration: no run history or retries, and durability is limited |
| Thousands of community nodes, many for IoT and MQTT | Flows are hard to review; nothing exists for agents or approvals |

**Worth taking:** message-source triggers (MQTT, files). See the event triggers issue. Node-RED is
the proof that a small streaming server can run on the edge, and Yukta's native image (150 MB
idle) is in the same class.

### Argo Workflows

A Kubernetes-native engine: each step is a pod, and workflows are CRDs written in YAML.
Apache 2.0, CNCF.

| Pros | Cons |
|---|---|
| Scales with the cluster; artifacts, memoization, DAG and steps | Needs Kubernetes, and each step pays the cost of starting a pod |
| Strong in ML and CI on Kubernetes | Large workflows strain etcd ("Support larger workflows", 28 👍) |

**Most-wanted:**

| Request | Yukta |
|---|---|
| Multi-cluster workflows (77 👍) | Remote workers (#433) are Yukta's equivalent |
| Fine-grained RBAC (56) | #704 |
| A Common Workflow Language standard (43) | Not planned |
| Pass a Kubernetes secret as an input (30) | Yukta has `decrypted:` |
| Hide secrets in logs (17) | Missing → [secret scrubbing issue](#issues) |
| Memoization storage (15) | Covered by #468 |
| Dark mode | Yukta already has it |

### Conductor OSS (formerly Netflix Conductor)

JSON workflow definitions, a JVM server (Spring Boot, JDK 17+), Redis for state and queues,
Elasticsearch or OpenSearch for search. Apache 2.0, and Orkes offers a hosted version.

| Pros | Cons |
|---|---|
| JSON definitions and a large set of system tasks; long production history | The Docker stack recommends at least 16 GB of RAM (Redis, Elasticsearch, server, UI) |
| Workers in any language through polling | Its tracker shows hung sub-workflows, wrong fork queue counts and index growth |

**Most-wanted:**

| Request | Yukta |
|---|---|
| Audit logging of workflow operations | Yukta has `GET /api/control/audit` |
| Periodic clean-up of old history | Covered by #617 and #464 |
| Production-grade AMQP support | Missing → [event triggers issue](#issues) |
| Spring Boot 4 compatibility | Yukta is on Boot 4.1 already |

### Camunda 8 (Zeebe)

A BPMN engine. Zeebe is written in Java with its own replicated log; Operate and Tasklist are
separate apps. The licence changed in 2024: self-managed production now needs a commercial licence.

| Pros | Cons |
|---|---|
| BPMN standard, forms, task lists, analytics | Heavy cluster (Zeebe, Elasticsearch, Identity, Keycloak) |
| Built for business processes | Licence is restrictive since 8.6; BPMN is overkill for developer automation |

**Worth taking:** nothing new. Yukta's approvals already cover the lightweight human-in-the-loop case.

### AWS Step Functions

Managed state machines in JSON (Amazon States Language).

| Pros | Cons |
|---|---|
| No servers; direct integration with more than 200 AWS services | AWS only; you pay per state transition; local testing is weak |
| Express workflows for high volume | Hard to debug outside the console |

**Worth taking:** a definition as JSON that can be tested offline. See the workflow test harness
issue. Step Functions' own TestState API shows that users want to test single states.

### Inngest, Hatchet and Trigger.dev

Durable functions for application developers (TypeScript, Python, Go), which are often used for AI
jobs.

| Pros | Cons |
|---|---|
| Flow control built in: concurrency keys, throttle, rate limit, debounce, priority, batching (Inngest) | Code-first |
| Hatchet: PostgreSQL only, up to 10k tasks/s queued on one instance (vendor figure) | Throughput depends on database size and tuning |
| Waiting for an event does not hold a concurrency slot | Server parts of some are open-core or source-available |

**Worth taking:**
- Concurrency keys, throttle and debounce → [issue](#issues). This is the most clearly missing
  admission feature in Yukta. Yukta already has a global cap, a cap per workflow and priority.

### LangGraph, Dify and Flowise

Agent graphs. LangGraph is a library with optional Agent Server; Dify and Flowise are visual LLM-app
builders.

| Pros | Cons |
|---|---|
| LangGraph: checkpoints at every super-step, time travel, interrupts, PostgreSQL checkpointer | Python or JS library; durability only at checkpoint boundaries |
| Dify: RAG, agent nodes, MCP runtime, can publish a workflow as an MCP server | Dify's licence adds conditions to Apache 2.0; little that is general-purpose |
| | Several sources say they struggle with long-running multi-agent state |

**Most-wanted in LangGraph's tracker:** PostgreSQL checkpointer robustness and losing streamed state
when a run is cancelled. Both are about durability and streaming, which supports #465 and #569.

**Worth taking:**
- Time travel, that is, forking a run from a checkpoint with edited state → [replay with an edited
  input](#issues).
- Publishing a workflow as an MCP tool → [issue](#issues).
- Natural-language authoring → [issue](#issues). Yukta already has the MCP prompt
  `create-session-config`; this would bring it into the UI.

## Performance and resource use

Published figures. **None of these were measured here, and the workloads differ.** For Yukta, see
`docs/operations/capacity-and-performance.mdx` (k6, native `0.1.0-alpha.2`, 2 vCPU pinned).

| Tool | Runtime | Smallest useful deployment | Memory (idle, or the stated minimum) | Published throughput | Source and confidence |
|---|---|---|---|---|---|
| **Yukta** (native) | GraalVM native, 1 process | 1 binary, in memory | **~150 MB idle; 304-360 MB peak under load** | **400 starts/s for 90 s, 0 refused, 0 stuck; e2e p95 80 ms** (tiny workflows) | Own k6 tests, measured |
| Yukta (jar) | JVM 25 | 1 jar | Higher; not published | Usually faster than native after warm-up (perf/README) | Own, not published |
| Temporal | Go, 4 services and a database | Server, PostgreSQL, UI | ≥4 GB, 2 vCPU for Docker Compose (third party) | ~1k state transitions/s with default shards; ~4.5k after tuning, on PostgreSQL (second-hand) | Low |
| Kestra | JVM 21 | Server and PostgreSQL | **~4 GiB, 2 vCPU** | 40 short tasks in ~6 s (Windmill's benchmark, Kestra 0.22) | Docs: high; benchmark: vendor |
| Airflow 3 | Python, scheduler, API server, workers, database | Compose stack | **≥4 GB, 8 GB ideal** | 40 short tasks in ~56 s (Windmill's benchmark) | Docs: high; benchmark: vendor |
| Prefect | Python, server and PostgreSQL | Server and a worker | Not published | 40 short tasks in ~4.9 s (Windmill's benchmark, Prefect 2.14) | Vendor |
| Windmill | Rust server and workers, PostgreSQL | 3 containers | ~1 GB idle in total (third party); 1-2 GB per worker advised | 40 short tasks in ~2.1-2.4 s (own benchmark) | Vendor |
| n8n | Node.js; Redis in queue mode | 1 process and SQLite or PostgreSQL | ~300-500 MB idle (blogs); 2 GB minimum, 4-8 GB advised | **220 executions/s** on a 4 GB c5a.large (trivial workflow) | Official |
| Node-RED | Node.js | 1 process | **~80 MB** | Not published | Blog |
| Conductor OSS | JVM 17, Redis, Elasticsearch | Compose stack | **≥16 GB** advised for the stack | Not published | Docs |
| Hatchet | Go, PostgreSQL | Engine and PostgreSQL | DB of 4 CPU and 8 GB for "hundreds/s" | Up to 2k events/s on 8-CPU RDS; 10k tasks/s claimed | Vendor |
| DBOS | Library and PostgreSQL | No server | Not applicable | 43k workflows/s on 96-vCPU RDS | Vendor |
| Argo Workflows | Go controller on Kubernetes | Kubernetes cluster | Controller is small; each step is a pod | Limited by pod start-up and etcd | Docs |
| Camunda 8 | Java; Zeebe, Elasticsearch, Identity | Cluster | Several GB (multi-service) | Not compared | Low |

**What this means for Yukta:**

- **Footprint is a real differentiator.** Every other orchestrator that keeps run history needs a
  database and 1-16 GB of RAM. Yukta's in-memory default and its native binary sit in the class of
  Node-RED and a single n8n process, but Yukta has run history, controls and MCP. Say this in
  positioning, and keep it true when Plus persistence is on. The PostgreSQL store has no benchmark
  yet (#620, #612, #613).
- **Throughput is competitive for a single node.** 400 starts/s with 0 refused on 2 vCPU is above
  n8n's official 220/s on a similar box. The workloads differ, so do not publish that as a
  comparison without running the same workflow on both.
- **Gaps to close before anyone can claim a performance lead:**
  - a jar benchmark (only native is published);
  - a benchmark of the PostgreSQL-backed Plus edition;
  - a test with nodes that do real I/O;
  - a run like Windmill's "40 lightweight tasks" so Yukta can be placed in its public table.
  
  The performance plan (#435) already owns this. No new issue was filed.

## User-requested features: can Yukta take them?

Requests that recur across trackers, grouped. **Status:** ✅ exists, 🟡 partly there or covered by an
open issue, ❌ missing (a new issue was filed).

| Request (seen in) | Yukta today | Status | Feasibility and effort |
|---|---|---|---|
| Retry options by failure type (Temporal #131) | `engine.retry.retryOn` / `noRetryOn` | ✅ | n/a |
| Priority queues (Temporal #1507, Inngest) | `yukta.priority`, live priority change | ✅ | n/a |
| Concurrency limit per workflow (Temporal #7666, Kestra, Argo) | `max-concurrent-per-workflow`, queue or reject | ✅ | n/a |
| **Concurrency by key, throttle, debounce** (Inngest, Kestra) | none | ❌ | Feasible in `ExecutionScheduler`; M |
| Versions, diff, rollback (Kestra) | yes | ✅ | n/a |
| Retry a group or restart from a step (Airflow, Conductor) | restart from node, retry node | ✅ | n/a |
| Human approval and waits (Camunda, LangGraph) | `WAIT_FOR_SIGNAL`, `requireApproval` | ✅ (in memory, #567) | n/a |
| Dark mode (Argo) | theme toggle | ✅ | n/a |
| Audit of operations (Conductor) | `/api/control/audit` | ✅ | n/a |
| Tool errors returned to the agent (n8n) | `failureMode: CONTINUE` | ✅ | n/a |
| **Hide secrets in logs** (Argo, Windmill) | API reads are masked; logs and results are not | ❌ | Feasible: scrub known resolved values at the log sink; S-M |
| **Unit tests for workflows** (Windmill top request, Step Functions TestState) | none for workflows; #637 is for plugins | ❌ | Feasible: run with stubbed node types and assertions; M |
| **Start on another workflow's end** (Kestra flow trigger, Temporal #680, Airflow assets, Dagster) | `SUB_WORKFLOW` only (parent-driven) | ❌ | Feasible: a trigger that subscribes to the event feed; M |
| **Webhook trigger** (n8n, Windmill, Kestra) | `POST /api/workflow/start` with an API key | ❌ | Feasible: a per-workflow path and HMAC; M |
| **Queue and file triggers** (Kestra realtime, Conductor AMQP, Node-RED MQTT) | `SCHEDULE_TRIGGER`, `API_TRIGGER`, `CONSTANT_SOURCE` | ❌ | Feasible as plugins; M each (Kafka, AMQP, MQTT, file watch) |
| **OpenTelemetry export** (Prefect, Dagster) | Micrometer observations; no exporter shipped | ❌ | Feasible: add the OTLP bridge and settings, check native; S-M |
| **Git sync** (Airflow, Windmill, n8n, Kestra) | `yukta.bootstrap.dir` is read once at start-up | ❌ | Feasible: watch the folder, optionally pull git; M |
| **Gantt or timeline** (Airflow, Kestra, Dagster profiler) | task sequence list, no time axis | ❌ | Feasible in the UI from the node start and end times; S-M |
| **Replay with an edited input** (LangGraph time travel, Temporal reset) | restart from node replays recorded inputs unchanged | ❌ | Feasible: an override payload on restart-from-node; M |
| **Publish a workflow as an MCP tool** (n8n, Dify) | generic `start_workflow`, no payload (#574) | ❌ | Feasible in `mcp`: dynamic tools from a declared input schema; M |
| **Natural-language authoring in the UI** (Kestra Copilot, n8n AI builder) | MCP prompt `create-session-config` only | ❌ | Feasible: a UI panel plus validate before save; M-L |
| Backfill and catch-up of missed schedules (Kestra, Airflow) | not replayed | 🟡 #468, #658 | Feasible |
| Node or flow result caching, memoization (Prefect, Argo) | none | 🟡 #468 | Feasible |
| Saga or compensation | none | 🟡 #468 | Feasible |
| Vault or an external secret manager (Windmill) | env-backed `decrypted:` | 🟡 #468 | Feasible |
| Streaming LLM output | none | 🟡 #465 | Feasible |
| Typed payloads and schemas (Airflow typed XComs) | untyped | 🟡 #594 | Feasible |
| Large payload offload (Temporal #1492, Argo) | none | 🟡 #593 | Feasible |
| Plugins at run time | fixed at build | 🟡 #482 | Hard on native |
| Retention and clean-up (Prefect, Conductor) | trims the log | 🟡 #617, #464 | Feasible |
| Fine-grained RBAC, key expiry (Dagster, Argo) | roles on keys | 🟡 #704 | Feasible |
| Multi-cluster or scale-out (Argo) | remote workers | 🟡 #433 | In progress |
| Durable timers and waits | in memory | 🟡 #461, #567 | Plus |
| Common Workflow Language, BPMN | none | not planned | Out of scope |

## Issues

Filed under the epic **#848 "Competitive analysis follow-ups"**, each with type Feature, the
`enhancement` label and the module label:

| # | Issue | Module |
|---|---|---|
| #849 | Concurrency keys, throttle and debounce for workflow starts | core |
| #850 | Scrub resolved secret values from logs, results and events | core, security |
| #851 | A workflow test harness that runs with stubbed nodes and checks assertions | core, cli |
| #852 | `WORKFLOW_EVENT` trigger that starts a workflow when another one ends | plugins |
| #853 | `WEBHOOK_TRIGGER` with its own path for each workflow and HMAC signature checks | plugins, web |
| #854 | Message-source triggers for Kafka, AMQP, MQTT and file watch | plugins |
| #855 | Ship an OpenTelemetry (OTLP) exporter for traces and metrics | boot |
| #856 | Watch the bootstrap folder and optionally sync it from git | web |
| #857 | Run timeline (Gantt) view with the duration of each node | ui |
| #858 | Restart from a node with an edited input | core |
| #859 | MCP: publish selected workflows as named tools with an input schema | mcp |
| #860 | Natural-language workflow authoring in the UI | ui |

## Already tracked

These gaps already had open issues, so none were filed again. They are now sub-issues of #848 as
well: #468 (backfill, caching, saga, A2A, eval node, Vault), #658 (schedules forgotten on restart),
#465 and #568 (agent loop, streaming), #569 (resume cost), #617 and #464 (retention), #637 (plugin
test kit), #482 (run-time plugins), #704 (key lifecycle), #567 and #461 (durable waits), #469 (trace
id in progress) and #574 (MCP start payload).

Related but kept under their own parents: #593 (claim check) and #594 (typed payload) belong to the
beta-readiness epic #741, and #433 (remote workers) and #435 (performance baseline) are epics
themselves.

## Sources

- Kestra requirements: <https://kestra.io/docs/administrator-guide/requirements>; concurrency: <https://kestra.io/docs/workflow-components/concurrency>; backfill: <https://kestra.io/docs/concepts/backfill>; SLA (0.20): <https://kestra.io/blog/release-0-20>
- Windmill benchmarks (vendor): <https://windmill.dev/docs/misc/benchmarks/competitors>, <https://www.windmill.dev/docs/misc/benchmarks/competitors/results/conclusion>; scaling: <https://windmill.dev/docs/advanced/scaling>; Railway idle measure: <https://railway.com/deploy/windmill-workflows>
- Airflow Docker Compose memory: <https://airflow.apache.org/docs/apache-airflow/3.3.2/howto/docker-compose/index.html>
- n8n performance: <https://docs.n8n.io/hosting/scaling/performance-benchmarking>; sizing blog: <https://www.cherryservers.com/blog/n8n-self-hosting-requirements>
- Temporal self-host (third party): <https://automationatlas.io/guides/tutorial-temporal-self-hosted-deploy-2026/>; throughput (second-hand): <https://markaicode.com/benchmarks/temporal-production-benchmark-latency/>; Worker Versioning GA: <https://temporal.io/blog/ga-worker-versioning-public-preview-upgrade-on-continue-as-new>; Update-With-Start: <https://temporal.io/blog/durable-digest-july-2025>
- Hatchet benchmarks (vendor): <https://hatchet.run/launch-week-01/benchmarks>, <https://docs.hatchet.run/self-hosting/improving-performance>; DBOS (vendor): <https://www.dbos.dev/blog/benchmarking-workflow-execution-scalability-on-postgres>
- Inngest flow control: <https://inngest.com/docs/guides/flow-control>
- Conductor OSS Docker: <https://docs.conductor-oss.org/devguide/running/docker.html>
- Node-RED footprint (blog): <https://raspberry.tips/en/smart-home/node-red-raspberry-pi-2>
- LangGraph persistence: <https://docs.langchain.com/oss/python/langgraph/checkpointers>
- Dify, Flowise and n8n comparison (blog): <https://rapidclaw.dev/blog/low-code-ai-agent-platforms-compared-2026>
- Top-voted issues: GitHub trackers of temporalio/temporal, kestra-io/kestra, apache/airflow, PrefectHQ/prefect, dagster-io/dagster, windmill-labs/windmill, argoproj/argo-workflows, n8n-io/n8n, langchain-ai/langgraph and conductor-oss/conductor, each sorted by 👍 (2026-10-23)
- Yukta: `docs/operations/capacity-and-performance.mdx`, `docs/running/admission-and-limits.mdx`, `docs/running/observability.mdx`, `docs/building/scheduling.mdx`, `docs/operations/security.mdx`, `core/.../model/workflow/NodePolicy.java`, `core/.../service/control/ExecutionScheduler.java`, `web/.../startup/BootstrapRunner.java`, `plugins/processors/mcp-tool-call/.../McpToolCallPlugin.java`
