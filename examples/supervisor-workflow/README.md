# Supervisor as a workflow

A supervisor can be a workflow. It lives in an **internal session** (a session tagged `yukta.internal=true`), so it does not
show up in the default session lists and its own events never reach a supervisor. For each event the profile lists, the server
starts the workflow with the event (secrets masked) and reads what the workflow ends with.

| File | What |
|---|---|
| `session.json` | The internal session `supervisors` and its workflow `low-risk-answers`. |
| `profile.json` | A profile of type `workflow` that starts it on `InputRequested`. It may send only `AnswerInputCommand` and `EscalateInputCommand`; a workflow supervisor sends nothing until its profile names commands. |
| `binding.json` | Attaches the profile to one session (change `my-session`). |

The workflow ends in an `OUTCOME_TERMINAL`:

- `SUCCESS` with a payload `{"commands": [{"type": "AnswerInput", "requestId": ..., "decision": ...}]}` sends those commands.
  Types that can always be sent: `AnswerInput`, `EscalateInput`, `PauseWorkflow`, `ResumeWorkflow`, `PauseNode`, `ResumeNode`, `SetPriority`.
  `StopWorkflow`, `StopNode`, `SkipNode`, `Restart`, `RestartFromNode`, `SetLimits` and `SetBreakpoint` only when `allowedCommands` names them.
- `ESCALATE` hands the request in the event up, with the outcome reason as advice.
- `RETRY` runs it once more. `FAILED`, a timeout or an unreadable result mean no opinion.

```bash
# the session is internal, so ask for it with includeInternal
curl -X POST localhost:9858/api/sessions -H 'Content-Type: application/json' -d @session.json
curl -X POST localhost:9858/api/supervisors/profiles -H 'Content-Type: application/json' -d @profile.json
curl -X POST localhost:9858/api/supervisors/bindings -H 'Content-Type: application/json' -d @binding.json
curl 'localhost:9858/api/sessions?includeInternal=true'
```

Defining sessions and attaching supervisors needs an `ADMIN` key. A request raised by a supervisor workflow itself reaches only people and
supervisors whose scope strictly covers the supervisor that started it, and that supervisor may not answer it. Status: this example
was written against the unit-tested pieces and has not been run end to end.

To make the decision with more than a rule, put an `LLM_CALL` node, or an `MCP_TOOL_CALL` that reads other runs with a **VIEWER** key,
in front of the terminal.
