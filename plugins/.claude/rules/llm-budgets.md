---
paths:
  - "processors/llm-call/**"
  - "processors/mcp-tool-call/**"
---

# LLM budgets

Moved from `core/CLAUDE.md`; loads when you touch `LLM_CALL` or `MCP_TOOL_CALL`.

### LLM budgets
`LLM_CALL` takes `budget: {maxTokens, maxCost}` (tokens are input plus output; `maxCost` needs
`prices`). Use is counted per run across all `LLM_CALL` nodes (the plugin keeps the newest 10000
runs); once a limit is reached the next call fails like a failed call (`failureMode` and
`routeByStatus` apply, so a workflow can fall back). With `budget.hardStop: true` the next call ends the
whole run instead: it throws `RunAbortedException`, which `NodePolicies` never retries, drops or
routes and `failureMode` does not absorb. Every refusal is counted in `llm.budget.exceeded`. One call
may overshoot the limit. `yukta.llm.rate-limit.<provider>` (requests per minute, e.g.
`yukta.llm.rate-limit.openai=60`) spaces the calls to a provider across all nodes and runs; a call
that would wait longer than its own timeout fails. The ledger (`RunBudgets.shared()` in plugin-api) is shared with `MCP_TOOL_CALL`, whose `budget` takes `{maxCalls, agent, hardStop}`; `LLM_CALL` also takes `maxCalls`, and a budget with an `agent` is counted apart from the rest of the run. Token and cost are counted per provider in `llm.tokens` and `llm.cost`.

### ask_for_input
`LLM_CALL` with `allowAskInput: true` offers the model the tool `ask_for_input` (`ToolSpecs.askInput`); like every tool it comes
back in `actions` and is run by `MCP_TOOL_CALL` with `allowAskInput: true` (not by the MCP server). That node makes it an input
request: effective risk = max(model risk, `inputRiskFloor`, default MEDIUM); audience, ttl, route come only from the node
(`inputAudience`, `inputTtlSeconds`, `inputRoute`); at most `inputMaxAsks` (3) per node run; the prompt and options are cleaned and
capped. No answer in time is a tool error (`inputOnTimeout: FAIL` fails the node). A wait uses no tokens; the node timeout includes the ttl.
The answer is text a person or agent wrote: pass it on as a tool result, never as a system instruction. The call counts toward `budget.maxCalls`.
