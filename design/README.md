# Design

Why Yukta is built the way it is. Start with [architecture.md](architecture.md) for the picture ([c4-architecture.md](c4-architecture.md) has it as C4 diagrams), then read the ADR of the area you touch.

How the code works, and how to use it, is not here: see the module guides in [`docs/contributing/`](../docs/contributing/), the reference in [`docs/reference/`](../docs/reference/) and the `CLAUDE.md` of each module. Open work is in GitHub issues, not here.

## What belongs here

| Folder | What | Rule |
|---|---|---|
| [`decisions/`](decisions/) | Architecture Decision Records (ADRs): one file per decision area, with the options that were rejected | The source of truth for *why*. |
| [`plans/`](plans/) | A long plan kept **only while its epic has unbuilt phases** and open issues cite its sections. Today: the plans of ADRs 0001 to 0004, 0011 and 0012, and `agent-efficient-run-queries.md` (token-efficient run reads for agents, no ADR yet) | Delete the plan when the epic closes; the ADR stays. |
| [`security/`](security/) | Security reviews against an ADR | One file per review, dated. |
| [`research/`](research/) | Notes that informed decisions (competitor analysis) | Dated; not maintained after. |

## ADR rules

1. One ADR per decision area, numbered in order of decision (`NNNN-short-name.md`), from [TEMPLATE.md](decisions/TEMPLATE.md).
2. An ADR records decisions and reasons, not phase lists or code inventories. Those go stale; the code and the issues are the source for them.
3. Do not rewrite an accepted ADR to change its meaning. Add a new ADR and mark the old one `superseded by`. Fixing a status line, a link or a typo is fine.
4. Link the epic. Link the module guide or reference page that describes the built result.
5. Docs must not claim more than the code does: say what is built.

## ADR index

| ADR | Decision area | Status |
|---|---|---|
| [0001](decisions/0001-input-requests.md) | Input requests and supervisor coordination | Accepted; phases 0 to 3a merged, later phases open (plan kept) |
| [0002](decisions/0002-supervisor-engine-split.md) | Supervisors own, engines decide | Accepted; built through the S-phases (plan kept) |
| [0003](decisions/0003-notifications.md) | Notifications and inbox | Accepted (plan kept) |
| [0004](decisions/0004-agent-console.md) | Agent console | Accepted; artboard review open (plan kept) |
| [0005](decisions/0005-editions.md) | Community, Plus and Enterprise editions | Accepted; Enterprise repository not started |
| [0006](decisions/0006-remote-workers.md) | Remote workers | Accepted; core built, operations open |
| [0007](decisions/0007-releases.md) | Release and install channels | Accepted; alpha is linux/amd64 and GHCR |
| [0008](decisions/0008-community-plugins.md) | Community plugins outside the repository | Accepted; built |
| [0009](decisions/0009-supervisor-catalog.md) | Supervisor catalog | Accepted; partly superseded by 0002 |
| [0010](decisions/0010-ai-backend-and-agent-session.md) | Unified AI backend and `AGENT_SESSION` | Accepted; registry and plugin built, later phases open |
| [0011](decisions/0011-test-tiers.md) | Integration and end-to-end test tiers | Accepted (plan kept while the epic is open) |
| [0012](decisions/0012-workflow-test-runs.md) | Test runs from the workflow editor | Accepted; backend built, editor UI in progress (plan kept) |

Other files: [security/supervisor-threat-model.md](security/supervisor-threat-model.md), [research/competitive-analysis.md](research/competitive-analysis.md).
