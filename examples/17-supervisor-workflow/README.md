# Supervisor as a workflow

A supervisor can be a workflow. It lives in an **internal session** (a session tagged `yukta.internal=true`), so it does not
show up in the default session lists and its own events never reach a supervisor. For each event the profile lists, the server
starts the workflow with the event (secrets masked) and reads what the workflow ends with.

| File | What |
|---|---|
| `session.json` | The internal session `supervisors` and its workflow `low-risk-answers`. |
| `supervisor.json` | A supervisor of type `workflow` that starts it on `InputRequested`. It may send only `AnswerInputCommand` and `EscalateInputCommand`; a workflow supervisor sends nothing until its profile names commands. |
| `attachment.json` | Attaches the supervisor to one session (change `my-session`). |
| `section-supervisor.json`, `section-attachment.json` | A second, narrower supervisor: the workflow `section-triage` bound to the section `build.*` of `my-workflow`. It only escalates. |

The workflow starts at a `SUPERVISOR_SIGNAL` and ends in a `SUPERVISOR_DECISION` (the same exits as `OUTCOME_TERMINAL`, and a command the contract cannot read fails the run instead of being dropped):

- `SUCCESS` with a payload `{"commands": [{"type": "AnswerInput", "requestId": ..., "decision": ...}]}` sends those commands.
  Types that can always be sent: `AnswerInput`, `EscalateInput`, `PauseWorkflow`, `ResumeWorkflow`, `PauseNode`, `ResumeNode`, `SetPriority`.
  `StopWorkflow`, `StopNode`, `SkipNode`, `Restart`, `RestartFromNode`, `SetLimits` and `SetBreakpoint` only when `allowedCommands` names them.
- `ESCALATE` hands the request in the event up, with the outcome reason as advice.
- `RETRY` runs it once more. `FAILED`, a timeout or an unreadable result mean no opinion.

**The chain.** A request from a node `build.test` goes to the section supervisor first. It ends with `ESCALATE`, so the request moves
to the next tier, where `supervisor:parent` selects the parents of the section supervisor (give `section-supervisor.json` `"parents": ["low-risk-workflow"]`): here the session
supervisor `low-risk-workflow`, which approves a LOW risk request. Anything else goes on to a person. A supervisor cannot be its own ancestor (saving refuses a cycle), so the chain cannot loop. Route the request with
`yukta.input.routes.default` tiers `supervisor:*`, `supervisor:parent`, then a `HUMAN` tier (see `docs/building/input-requests.mdx`).

```bash
# the session is internal, so ask for it with includeInternal
curl -X POST localhost:9858/api/sessions -H 'Content-Type: application/json' -d @session.json
curl -X POST localhost:9858/api/supervisors -H 'Content-Type: application/json' -d @supervisor.json
curl -X POST localhost:9858/api/supervisors/attachments -H 'Content-Type: application/json' -d @attachment.json
curl -X POST localhost:9858/api/supervisors -H 'Content-Type: application/json' -d @section-supervisor.json
curl -X POST localhost:9858/api/supervisors/attachments -H 'Content-Type: application/json' -d @section-attachment.json
curl 'localhost:9858/api/sessions?includeInternal=true'
```

Defining sessions and attaching supervisors needs an `ADMIN` key. A request raised by a supervisor workflow itself reaches only people and
supervisors whose scope strictly covers the supervisor that started it, and that supervisor may not answer it. Status: `WorkflowSupervisorAcceptanceTest` (in `boot`) runs `session.json` and `profile.json`
end to end: a LOW risk request is approved and the run goes on, a MEDIUM one is handed up with the reason as advice. The section
profile and binding are not part of that test.

To make the decision with more than a rule, put an `LLM_CALL` node, or an `MCP_TOOL_CALL` that reads other runs with a **VIEWER** key,
in front of the terminal.
