# 10 — Drive Yukta from an AI agent (MCP)

**Use case:** an AI coding agent (Claude Code, or any MCP client) creates a workflow,
starts it and reads the result without a human touching the REST API. Yukta exposes
its tools at `http://localhost:9858/mcp` (streamable HTTP).

## Connect an agent

```bash
claude mcp add --transport http yukta http://localhost:9858/mcp
# with an API key configured on the server (yukta.mcp.api-key):
claude mcp add --transport http yukta http://localhost:9858/mcp --header "Authorization: Bearer $YUKTA_API_KEY"
```

Tools the agent gets: `create_session`, `start_workflow`, `get_workflow_status`,
`get_workflow_history`, `get_execution_logs`, `control_workflow`, `control_node`,
`list_plugins`, `get_plugin_details`, `list_sessions`, `get_session_details`, …

Prompt the agent with something like:

> Use the yukta MCP server. Create a session from `examples/01-hello-world/session.json`,
> start workflow `hello`, and tell me its final status.

## Run the same flow by hand

`run.sh` makes the same JSON-RPC `tools/call` requests the agent would:

```bash
./examples/10-mcp-agent/run.sh
```

It creates the session from `01-hello-world`, starts `hello`, waits for a terminal status,
reads the result through `get_workflow_history`, and fails unless the status is `SUCCESS`.

> Known issue: MCP output validation rejects executions that are still running (null
> `endTime` / `controlState`), so `get_workflow_status` and `get_workflow_history` only
> succeed after the run finishes. An agent should poll with a short delay, not right after start.
