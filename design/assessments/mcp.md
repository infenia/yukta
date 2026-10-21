# MCP: assessment

How the `mcp` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/mcp.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Amcp), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Amcp) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Amcp) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

A suggested order, driven by risk rather than by size. The first steps are small and close the largest exposures.

1. **Today.** 23 tools, stateless /mcp, the same named keys and roles as REST (optional), per-tool role check, session-limited keys, ownership check, bounded log reader with a cursor, polling for progress.
2. **Close the cheap gaps.** Done: refuse non-loopback without a key. Next: make one setting cover REST and MCP, add idempotencyKey, payload and stop mode to the tools, fix both creation guides and test them, show config fields and config-aware ports in plugin details. Risks: `apiKeyOpen`, `noIdempotency`, `stopParity`, `sessionGuideWrong`, `pluginGuideWrong`, `portsNoConfig`, `configSchemaHidden`.
3. **Know who is calling.** Done: named keys with roles, sessions and an actor. Next: a rate limit, a log or counter for rejected calls, and a test that the role map covers every tool. Risks: `noActor`, `noRateLimit`, `bodyReparse`, `rejectsUnlogged`.
4. **Safer authoring and reading.** Overwrite flag and dry-run for create_session. Mark log lines as data. Paging for history, sessions and logs. Risks: `overwriteSession`, `logInjection`, `noPaging`.
5. **Live and cheap status.** A one-pass summary in core for the status tool. A way to wait for or subscribe to changes, so agents stop polling. Risks: `healthLinear`, `noPush`, `signalNotState`.
6. **Keep it honest.** Generate the tool table from annotations in a test, add a parity check with web, re-enable PMD on tests. Risks: `docsDrift`, `pmdTestOff`.

## Quality lenses

### Scalability

mcp is a stateless shell, so it scales as far as core does: any instance could serve any request. The pressure is that agents poll, and some calls grow with the number of sessions.

**Ceilings today**

- Status and list tools read every session with no paging
- Polling load grows with agents x runs, because nothing is pushed
- Whole-log reads to find a tail on very large logs
- All tool descriptions are sent to every client on connect

**Levers today**

- No state, no sticky sessions
- 8-at-a-time session reads
- Streaming tail with a 5,000-line cap

**Needs design work**

- Paging on list and history tools
- A cheaper status path in core (one-pass summary)
- Progress notifications or a resource subscription instead of polling

### Performance

Each call is one or a few reads in core, so latency is mostly core's. The costs worth watching are log scans, the aggregate status and the ownership read on every execution call.

**Ceilings today**

- Log filter scans every line of an execution
- Status cost is linear in sessions and history size
- Regex budget of 50 ms per line, times many lines, can still add up

**Levers today**

- Regex compiled once per call
- One pass per session in the aggregator
- Ownership read is a single record lookup

**Needs design work**

- A line or time bound on the whole filter, not only per line
- Cache or summary for the status snapshot
- Server-side paging for history

### Availability

Almost nothing here can fail by itself, since there is no state. Availability is core's. mcp's own risk is a boot failure from the framework and a flood of agent calls with no rate limit.

**Ceilings today**

- Whole app fails to start if schema generation breaks
- No rate limit per caller
- No health signal for the MCP endpoint itself

**Levers today**

- Stateless: a restart is invisible to a polling agent
- Broken sessions are skipped in listings

**Needs design work**

- A per-key rate limit
- A startup smoke test that lists tools (exists: the integration test)
- Metrics for tool calls and errors

### Security

This is the lens that matters most. The endpoint can install workflows that run commands, and it ships open. The module fences agent input (regex, size), keeps ownership consistent and sets honest hints. It has no caller identity or authorisation.

**Ceilings today**

- Keys are static configuration; the mcp key is a full ADMIN key
- Open when no key is set (loopback only)
- Role is decided by a filter that re-reads the request body
- Rejected requests are not logged or counted

**Levers today**

- Constant-time key compare when set
- Same error for unknown and foreign executions
- Destructive and open-world hints, and a confirm request on create_session
- Regex length and time limits

**Needs design work**

- Name keys per agent and avoid yukta.mcp.api-key
- A log or counter for rejected calls
- A test that the role map covers every tool
- Expiry and revocation without a restart

### Operability

Errors are written for agents and sit in the server log too, but the module has no metrics, no audit of its own and little visibility into what agents are doing.

**Ceilings today**

- No call counts, error counts or latency per tool
- Skipped sessions only appear as log warnings
- Hand-written guides can drift without any alarm

**Levers today**

- Actionable error text naming the next tool
- Warnings logged for skipped sessions
- Test of the tool list on the real endpoint

**Needs design work**

- Per-tool metrics and a request log with agent identity
- A 'skipped sessions' field in the status result
- Tests that compile guide templates and validate the example config

### Evolvability

The shape is simple and cheap to change. Adding a tool is a method and a provider call. The danger is surface drift: web, mcp and the plugin API move on their own, and the module's text does not follow.

**Ceilings today**

- Parity with REST is by hand (payload, idempotency, stop mode already differ)
- Framework quirks are encoded in conventions, not in types
- PMD is switched off for test code

**Levers today**

- Thin facade over providers
- Records for every result
- Integration test on real JSON-RPC

**Needs design work**

- A parity checklist or shared contract with web
- Generated or tested guides
- Re-enable pmdTest and fix its findings

## Questions to settle

- **Security.** Today the endpoint is open and relies on a loopback bind. Is that an acceptable default for a tool that can install command-running workflows, and what is the smallest change that makes it safe? (risk `apiKeyOpen`)
- **Identity.** If an incident happens, how would you tell whether an agent or a person stopped a run? What would you need to add? (risk `noActor`)
- **Trust boundary.** Access is decided by a filter that reads the request body before the MCP runtime does. What could make the two readings differ, and what would you test? (risk `bodyReparse`)
- **Failure.** An agent's start_workflow call times out. What should it do next, and what could it break by doing it? (risk `noIdempotency`)
- **Prompt injection.** A plugin logs text from an external web page. How could that text change what the agent does next? (risk `logInjection`)
- **Design.** Is it right for the tool description to carry the safety rules (confirm first), or must the server enforce them? (risk `overwriteSession`)
- **Scale.** You have 500 sessions and 20 agents polling the status tool every 5 seconds. What breaks first? (risk `healthLinear`)
- **Protocol.** What would you give up, and what would you gain, by moving to a stateful transport for live progress? (risk `noPush`)
- **Quality.** How would you test that the creation guide stays valid when the plugin API changes? (risk `pluginGuideWrong`)
- **Operations.** A control call says 'Pause signal sent.' When is it safe for the agent to assume the node is paused? (risk `signalNotState`)

## Last risk review

No backlog IDs are used here: the earlier review findings (P-1 to P-14) are all fixed and are not listed. Risk titles are plain names. Items worded 'In our view' are judgement, not a measured defect. Review of 2026-10-15 against main (after PR #373): 2 risks closed (sharedKey, sessionBoundary), 3 partly closed (apiKeyOpen, noActor, noPaging), 2 added (bodyReparse, rejectsUnlogged); the others are unchanged because their code is unchanged.
