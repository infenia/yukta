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
