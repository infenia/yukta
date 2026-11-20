# 16 — Watch and talk to agents (`AGENT_SESSION` conversations)

**Use case:** a workflow runs several AI agents, one after the other, and a person wants to follow all of them in one place
and, when needed, talk to one. Each `AGENT_SESSION` message opens a **conversation**; the web UI, the CLI, MCP and supervisors
read and steer the same conversations.

This example runs two **scripted** agents (`agent-harnesses/fake-agent.json`, a shell one-liner), so it needs no account and
`run-all.sh` can run it. It skips (exit 3) unless the server has the preset.

## Run it

```bash
YUKTA_PLUGINS_ENABLED=AGENT_SESSION YUKTA_PROCESS_ALLOWED_COMMANDS=sh \
  YUKTA_AI_PRESETS_DIR=$PWD/examples/3-paid-tools/16-agent-console/agent-harnesses ./gradlew bootRun
./examples/3-paid-tools/16-agent-console/run.sh
```

While it runs (or after), look at the conversations:

```bash
yukta-cli conversations list --execution <execution-id>
yukta-cli attach <execution-id>        # one timeline for both agents
curl -s "localhost:9858/api/conversations?executionId=<execution-id>" | jq
```

or open the **Agents** tab under the graph on the workflow page.

## The real chain

The same graph with real agents, each with its own preset, is a session like this (configure each one; the ids are the shipped
presets, `agy` needs Antigravity installed and logged in):

```
trigger ─► claude-code (model A) ─► claude-code (model B) ─► codex ─► antigravity (acp) ─► terminal (pty, any program)
```

```json
{ "nodeId": "build-fix", "type": "AGENT_SESSION",
  "config": { "harness": "terminal", "command": ["some-agent", "--task", "{prompt}"], "prompt": "${payload.task}" } }
```

Start the server with `YUKTA_PLUGINS_ENABLED=AGENT_SESSION`, allow the programs in `YUKTA_PROCESS_ALLOWED_COMMANDS`, pass what
each needs in `YUKTA_PROCESS_PASS_ENV` (`HOME`, API keys), and turn the terminal tier on with `YUKTA_AGENTS_PTY_ENABLED=true`
for the `terminal` harness. Who may steer a node is its `steerAudience` (default `role:OPERATOR`). See
[Run an AI agent](../../../docs/guides/ai/run-an-agent.mdx).
