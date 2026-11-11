# Page map (the contract all writers link against)

Route = path under `docs/` without `.mdx`. "From" = old pages whose facts to reuse (read them, then `git mv` the best one onto the new path and rewrite, or delete once absorbed). Writers: A1 learn, A2 concepts, A3 guides build/ai/supervise, A4 guides operate/extend, A5 reference. Orchestrator (me): index, docs.json, redirects, changelog, README, screenshots.

## Start here
| Route | Quadrant | From | Owner |
|---|---|---|---|
| index | home | index | me |
| get-started/install | guide | quickstart step 1, operations/deployment | A1 |

## Learn (tutorials, "we")
| learn/your-first-workflow | quickstart | A1 |
| learn/mix-rules-and-ai | building/ai-and-agents, plugins/decisions, examples 04, 09 | A1 |
| learn/let-an-assistant-build-a-workflow | interfaces/mcp, example 10, skills/yukta-workflows | A1 |
| learn/add-a-supervisor | running/supervisors, examples 16, 17 | A1 |
| learn/ask-for-approval | building/input-requests, building/approvals-and-signals, examples 03, 15 | A1 |
| learn/playground | playground | A1 |

## Concepts (explanation)
| concepts/how-it-works | the mental model: workflow, run, nodes, edges, ports, messages | concepts/how-it-works, nodes-edges-ports, messages | A2 |
| concepts/ai-and-deterministic-work | NEW: why one graph holds both; where each belongs; who decides at each step | building/ai-and-agents, plugins/decisions | A2 |
| concepts/supervisors | what a supervisor is; shell vs engine; ownership; operator access; hierarchy | running/supervisors, supervisor-engines | A2 |
| concepts/ai-assisted-configuration | NEW: the MCP server + skills; what an assistant can author, run, steer; roles and limits | interfaces/mcp, skills/* | A2 |
| concepts/who-decides | input requests, risk tiers, routing, HIGH is human only | building/input-requests | A2 |
| concepts/sessions-and-workflows | | concepts/sessions-and-workflows | A2 |
| concepts/runs | run lifecycle and statuses | concepts/executions | A2 |
| concepts/delivery-and-backpressure | | same | A2 |
| concepts/failure-model | | concepts/failure-handling (explanation part) | A2 |
| concepts/time-limits | | same | A2 |
| concepts/sub-workflows | | same (explanation part) | A2 |
| concepts/durability-and-high-availability | checkpoints, persistence, lease, standby | operations/checkpoints-and-resume, persistence, high-availability (explanation parts) | A2 |
| concepts/security-model | roles, keys, who can do what, threat posture | operations/security (explanation part) | A2 |
| concepts/compared-with-other-tools | | same | A2 |

## Guides (how-to, "you")
Build (A3): guides/build/define-a-workflow, route-and-branch, loop-and-retry, use-variables-and-expressions, validate-and-roll-back, schedule-a-workflow, run-a-sub-workflow, handle-failures, control-a-run (run-controls + run-modes)
AI (A3): guides/ai/call-an-llm, use-tools-and-mcp-servers, set-token-budgets, keep-session-memory, classify-gate-and-score (plugins/decisions how-to), connect-an-assistant (Claude Code, Gemini CLI, any MCP client), ask-for-input (ASK_INPUT, ask_for_input tool, wait for signal)
Supervise (A3): guides/supervise/assign-a-supervisor, write-a-rules-supervisor, write-an-agent-supervisor (http engine), write-a-workflow-supervisor, build-a-hierarchy, replay-a-supervisor, route-input-requests, use-the-control-dashboard
Operate (A4): guides/operate/deploy, upgrade-and-roll-back, persist-sessions, run-highly-available, add-remote-workers, secure-the-server, observe-runs, tune-capacity-and-limits, troubleshoot, use-the-web-ui, use-the-cli
Extend (A4): guides/extend/write-a-plugin

## Reference (facts only)
Plugins (A5): reference/plugins/overview, triggers, routing-and-flow, transform, external-io, ai, decisions, terminals
Workflow (A5): reference/workflow-json, reference/node-options, reference/expressions, reference/messages
Supervisors (A5): reference/supervisors/shell, engines, contract, rule-files
Interfaces (A5): reference/mcp-tools, reference/cli, reference/client-parity
Server (A5): reference/configuration (merge operations/configuration + reference/limits-and-defaults), reference/security (roles, headers, key facts), reference/status-values, reference/glossary, reference/licensing
API (unchanged, still `api/*`): overview, errors, sessions, workflows, executions, control, streams, catalogue (+ generated endpoints)
Changelog (me): reference/releases, reference/whats-new-alpha-4, reference/migration-alpha-4

## Shot names for `<UiShot name=…/>` (generated later)
sessions-list · workflow-canvas · workflow-editor · plugin-catalogue · supervisors-catalogue · supervisor-details · supervisor-chain · control-dashboard · inputs-panel

## Rules for every writer
1. Read `internal-style/readers.md`, `voice-and-terms.md`, `../DOCS-APPROACH.md` (sections 2, 4, 8) first. One quadrant per page; use the templates in DOCS-APPROACH §8.
2. Source of truth for behaviour is the code and tests on this branch (it is origin/main), not the old prose. Verify names, defaults, endpoints, error codes by grep. Where old docs and code disagree, follow the code and list the disagreement in your report.
3. Link only to routes in this map (or `api/*`). No links to `contributing/` or `internal/`; none to repo files except `https://github.com/infenia/yukta/...` for examples.
4. Do not hand-type version numbers except the pinned install command in `get-started/install` (keep its `releases/download/v<ver>/install.sh` and `ghcr.io/infenia/yukta:<ver>` forms: `.github/scripts/bump-docs.sh` rewrites them). Elsewhere write "the current release" or omit.
5. Components: `<ReaderState learn= time= need= end= />` (import from `/snippets/reader-state.jsx`, all pages but Reference), `<UiShot name= alt= caption= />` (import from `/snippets/ui-shot.jsx`), Mintlify `<Steps>`, `<Tabs>`, `<Note>`, `<Tip>`, `<Warning>`, `<CardGroup>`. Existing diagrams in `/images/diagrams/*.{light,dark}.svg` use the `yk-diagram` pair pattern from `quickstart.mdx`. Mermaid is allowed.
6. Do NOT edit `docs.json`, `index.mdx`, `api/*`, `snippets/*`, or files owned by another writer. Do NOT start workflows on the shared dev server at 127.0.0.1:9858 (it is the team's build server). Commands in tutorials must be checked against code, examples and the CLI source; list any you could not run under "Unverified" in your report.
7. When an old page is fully absorbed, `git rm` it. Append one line per moved/removed old page to `internal-style/redirects/<your-agent>.tsv` as `old-route<TAB>new-route` (create the directory).
8. No commits. Report: pages written, old pages removed, code/doc disagreements, unverified commands, missing facts.
