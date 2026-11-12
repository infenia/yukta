# 19 — Run an AI agent with approvals (`AGENT_SESSION`)

**Use case:** a step needs an AI agent (Claude Code or Codex) to work on a task, and a person, a supervisor or another agent
must approve what it does. The agent is configuration (`harness`); the approvals are the same input requests as
[`ASK_INPUT`](../../docs/guides/ai/ask-for-input.mdx).

This example has no `run.sh`: it needs a real agent CLI and an account, so `run-all.sh` skips it.

## The graph

```
fix:  trigger ─► agent (AGENT_SESSION) ─success────► done (SUCCESS)
                                       ─failure────► failed (ESCALATE)
                                       ─incomplete─► stopped-early (RETRY)
```

## Run it

1. Install and log in to the agent CLI (`claude`). Start the server with the plugin on and the executable allowed:

   ```bash
   YUKTA_PLUGINS_ENABLED=AGENT_SESSION YUKTA_PROCESS_ALLOWED_COMMANDS=claude ./gradlew bootRun
   ```

2. Find out how to configure it from the server itself, with no other documentation:

   ```bash
   curl -s localhost:9858/api/plugins | jq '.data[] | select(.type=="AGENT_SESSION")'
   # or the MCP tool get_plugin_details: options, ports, the harnesses and what each can do
   ```

3. Load the session and start the workflow:

   ```bash
   curl -X POST localhost:9858/api/sessions -H 'Content-Type: application/json' -d @examples/19-agent-session/session.json
   curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
     -d '{"sessionId":"example-19-agent-session","workflowId":"fix","payload":{"task":"List the files in this folder and say what each is for"}}'
   ```

4. The agent uses Read, Grep and Glob freely (`args`). Anything else it wants (a shell command, an edit) becomes an
   `APPROVAL` request. Open ones are listed in the Inputs panel of the workflow page and at `GET /api/inputs`; answer with
   `approve` or `reject` (`$EXEC` is the execution id, `$REQ` the request id):

   ```bash
   curl -X POST localhost:9858/api/executions/$EXEC/inputs/$REQ/answer -H 'Content-Type: application/json' \
     -d '{"decision":"approve","rationale":"looks fine"}'
   ```

   No answer within `approvalTimeout` seconds, or `reject`, refuses the tool; the agent sees the refusal and carries on or stops.

## Make it yours

- Another agent: `"harness": "codex"` (see the notes below). Adding a harness is a JSON file, not code: put it in the directory
  `yukta.ai.presets-dir` names; `harness` lists it, with its capabilities, in the plugin details.
- Who answers: `approvalAudience` (`["supervisor:ops"]`, `["role:OPERATOR"]`); `approvalRisk` decides whether a supervisor may
  answer on its own (never `HIGH`).
- Continue a conversation across messages: `"session": "${payload.ticket}"`; one turn runs at a time per key (`onBusy`).
- No approvals, the agent decides alone: remove `approvals` and give it the tools it needs in `args`.

## Notes

- Claude Code's preset was written from a recorded run. The Codex preset follows the documented `codex exec --json` events and has not
  been run against the real CLI; it reads only by default (add `"args": ["--sandbox", "workspace-write"]`), and it has no resume and
  no approvals.
- `AGENT_SESSION` is restricted: it starts a process on the server. Allow only the executables you mean to
  (`yukta.process.allowed-commands`), and give the agent a `workingDir` that holds nothing you mind it reading.
