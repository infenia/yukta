# Plugins: assessment

How the `plugins` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/plugins.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Aplugins), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Aplugins) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Aplugins) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

A possible order of work, safest edges first.

1. **Today.** 31 node types and four supervisor kinds on one JVM. Done since the first guide (PRs #373, #374): script sandbox with class allow-list and limits, command allow-list, env allow-list, restricted SpEL methods, LLM and MCP budgets, provider rate limit, tool approvals, PostgreSQL stores for KV_STORE and SESSION_MEMORY. Open: no host allow-list, plaintext secrets in config, no concurrency cap, memory-only defaults.
2. **Contain the sharp edges.** Done: script sandbox, command allow-list. Next: operator-set host and folder allow-lists, a max-concurrency setting, kill descendant processes on timeout, redact secrets in read APIs, make the agent guards a deployment default. Risks: `scriptHostAccess`, `ssrf`, `shellModel`, `orphans`, `fanout256`, `plaintextSecrets`, `fileSandbox`, `agentGuardsOptIn`.
3. **Bound memory and cost.** Evict caches and sessions, put LLM-backed decisions under the run budget (LLM_CALL and MCP_TOOL_CALL already are), derive timeouts for SQL and file plugins, add a connection pool, take SESSION_MEMORY off the reactive threads, truncate logged output. Risks: `unboundedMaps`, `sessionMemory`, `sqlTimeout`, `consoleLogs`, `sessionStoreBlocks`.
4. **Make state durable.** Done: PostgreSQL stores for KV_STORE and SESSION_MEMORY (opt-in). Left: make them the default or warn when memory-only, durable anomaly windows, a persistent scheduler, rules that reload. Risks: `kvVolatile`, `windowsPerRun`, `scheduleLost`.
5. **Clean the layering.** Move the resolver and helpers into plugin-api, validate loop targets, re-enable the gates, rename CONTENT-FILTER (done, without an alias), make control planes optional. Risks: `coreLayering`, `gatesOff`, `loopSkipsValidation`, `loopEscalate`, `typeIds`, `optionalNotOptional`.
6. **Decide the oversight model.** Alert on 'no opinion' from the model plane, prefer rules-first, treat model verdicts as advice. Risks: `modelFailsOpen`, `promptInjection`.

## Quality lenses

### Scalability

Per-message plugins scale with the JVM's threads and with the number of nodes, and most of the cost is waiting on outside systems. What does not scale is state: KV_STORE, SESSION_MEMORY and the control-plane windows live in one process.

**Ceilings today**

- Concurrency is per node (4 for process, 16 for HTTP, MCP, model and decision nodes, 1 for session memory); there is no limit shared across nodes or runs
- KV_STORE capped at 10,000 keys in one JVM; SESSION_MEMORY unbounded
- ModelControlPlane asks the model about each matching event, one at a time
- SQL_QUERY opens a connection per message

**Levers today**

- Caps on process output, file reads and SQL rows
- SPLITTER concurrency and AGGREGATE size are settings
- Optional modules keep unused code out of a build

**Needs design work**

- A max-concurrency setting on external plugins
- A store interface so KV_STORE and SESSION_MEMORY can be database-backed
- A connection pool for SQL_QUERY

### Performance

Pure plugins (FILTER, BRANCH, PROJECTION) are cheap and cache parsed expressions. The cost is in model calls and outside requests, which take seconds, not microseconds.

**Ceilings today**

- A model call per message (decision plugins, LLM_CALL) dominates latency and cost
- SCRIPT mode creates a fresh GraalJS context per message
- A Handlebars or SpEL parse is cached forever, so memory, not CPU, is the limit
- File and SQL work uses a shared pool for blocking work

**Levers today**

- Compiled scripts and templates are reused
- SpEL cache is a 1,000-entry LRU
- Model clients are reused per configuration
- Timeouts bound every external wait

**Needs design work**

- Rate limits for hosts and non-model plugins (model providers have yukta.llm.rate-limit)
- Budgets for LLM-backed decisions (LLM_CALL and MCP_TOOL_CALL have them)
- Benchmark figures (none were found in the module notes)

### Availability

Plugins are as available as what they call. The module's job is to fail in a way the graph can handle: status ports, error ports, fallbacks. In-memory state does not survive a restart.

**Ceilings today**

- A failed model, webhook or database fails every message that needs it
- KV_STORE, session history and windows vanish on restart
- The model control plane fails open by design
- Schedules are not remembered across restarts

**Levers today**

- failureMode CONTINUE with routeBy ports for fallback paths
- LLM_CALL passes the payload on for model failover
- Engine retry and dead-letter ports apply on top

**Needs design work**

- Durable session and KV stores
- A circuit breaker for repeated failures of one outside system
- Persistent schedules

### Security

This is the lens that matters most. Plugins turn author-written configuration into running code, network calls, file access and database queries. Several guards exist (restricted SpEL, bound SQL parameters, file path checks, https for webhooks), but several are absent.

**Ceilings today**

- JavaScript has a class allow-list, plain-data inputs, a statement limit, a time limit, value-count and string-length limits, but no heap limit
- No host allow-list for HTTP_REQUEST, MCP_TOOL_CALL or NOTIFY_TERMINAL; PROCESS_EXECUTOR runs nothing unless yukta.process.allowed-commands is set (or allow-any is on)
- Secrets written in config are returned by read APIs as plaintext
- Untrusted text goes straight into model prompts

**Levers today**

- Restricted SpEL with no types or constructors
- decrypted: secrets from YUKTA_SECRET_ environment variables
- File sandbox with link checks; SQL bound parameters; https-only webhooks
- Deny rules that fail closed; optional modules

**Needs design work**

- Host and command allow-lists, set by the operator, not the author
- A memory limit for scripts and a host allow-list for outbound calls
- Secret redaction in read APIs
- A decision on who is trusted to author workflows

### Operability

Each external plugin counts successes and errors per node and publishes a usage description. MCP calls get a timer. Logging is uneven: some plugins log payloads and exception text in full.

**Ceilings today**

- CONSOLE_TERMINAL logs every payload at INFO
- PROCESS_EXECUTOR puts up to 10 MiB of output into error messages and logs
- Rule files need a restart to change
- No metrics for model cost totals

**Levers today**

- Per-node counters for most plugins
- A Micrometer timer yukta.mcp.call
- A generated config form from each plugin's field list
- Usage text that explains every setting

**Needs design work**

- Truncation of embedded output
- Reloadable rules
- Cost and token metrics
- Redacted logging

### Evolvability

Adding a plugin is easy: one bean, one config schema. The friction is below the surface: 11 of 19 plugin modules depend on core, two quality gates are off in the largest module, and type ids are not uniformly named.

**Ceilings today**

- Plugins compile against core classes, so core changes can break them
- internal-core holds 12 types and has PMD and Checkstyle-test disabled
- Per-plugin copies of UI card HTML
- Aliases do not exist if a type id is renamed

**Levers today**

- One contract; a registry that rejects duplicates
- A shared failure helper, metrics hook and config validators
- A tests-pin-the-field-names convention in the module notes

**Needs design work**

- Move resolver and helpers into plugin-api
- Re-enable the disabled gates
- A naming rule and an alias mechanism for type ids

## Questions to settle

- **Security.** If a workflow author is not fully trusted, which plugins would you remove from the build and which would you keep behind allow-lists? (risk `scriptHostAccess`)
- **Security.** Where should the host allow-list live: in each plugin's config, a deployment setting, or the network around the server? (risk `ssrf`)
- **Scalability.** What is the right default concurrency for a plugin that starts a program, calls a model, or reads a file, and who should set it? (risk `fanout256`)
- **Evolvability.** Is the 'typed spec, gateway, failure mode' pattern worth extracting into a base class, or does that hide the differences between a process, a web call and a tool call? (risk `coreLayering`)
- **Reliability.** Which of KV_STORE, SESSION_MEMORY, ANOMALY windows and schedules deserve durable storage first, and what would break without it? (risk `kvVolatile`)
- **Governance.** Should a model be allowed to spend money without a cap, and what is the unit of a budget: tokens, cost, calls or time? (risk `decisionUnbudgeted`)
- **Security.** Which verdicts in your workflows would hurt if an attacker could choose the answer, and should those use a model at all? (risk `promptInjection`)
- **Availability.** When the model control plane is down, should the system keep running unattended, or stop? (risk `modelFailsOpen`)
- **Functionality.** Should a loop be a plugin that calls other plugins, or a real sub-graph in the engine? (risk `loopSkipsValidation`)
- **Operability.** What should be in the logs for a failed external call: nothing, a summary, or everything the process printed? (risk `consoleLogs`)

## Last risk review

IDs in brackets such as (G1) are finding numbers from the module's review notes ([Improvements page](./plugins.md)); only findings still open in the code are listed. Ids without brackets are new findings from reading the code, some of them judgement. Review of 2026-10-15 against main (after PRs #373 and #374): 2 risks closed (secretBlacklist, llmBudget), 5 partly closed (scriptHostAccess, shellModel, sessionMemory, kvVolatile, promptInjection), 3 added (agentGuardsOptIn, decisionUnbudgeted, sessionStoreBlocks); 3 more (ssrf, fanout256, coreLayering) got updated facts; the other 14 are unchanged because their code is unchanged.
