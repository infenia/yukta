---
name: yukta-workflows
description: Use Yukta, a workflow server, through its MCP tools. Create a session of workflows, start a run, watch it, read its logs and result, debug a failed run, and steer a run (pause, stop, restart, recover, roll back a version). Use it when the user asks to build, run, watch, fix or stop a Yukta workflow, or mentions Yukta sessions, runs or approvals.
---

# Drive Yukta through MCP

Yukta runs workflows (a graph of nodes and edges) grouped in **sessions**. A **run** is one execution of a workflow, with an `executionId`.
You work with it only through the `yukta` MCP server's tools. If you have no `yukta` tools, connect the server first (see "Connect").

## First step: `whoami`

Call `whoami` before anything else. It says whether you are authenticated, your `role`, which `sessions` you may touch (`null` is all), and
`permissions` (`startRuns`, `controlRuns`, `editSessions`, `deleteSessions`, `changeLimits`, `sendCommands`). Plan with what it says; a call your
role does not allow is refused with HTTP 403, so do not try it, tell the user which role they need.

| Role | May call |
|---|---|
| `VIEWER` | only the read tools: the tools below marked `VIEWER` |
| `OPERATOR` | everything `VIEWER` can, and start and steer runs |
| `ADMIN` | everything, including `create_session`, `delete_session`, `delete_workflow`, `set_scheduler_limits` |

## Tools, the role each needs, and their arguments

Every tool that takes an `executionId` also takes the `sessionId` that owns it.

| Tool | Role | Arguments |
|---|---|---|
| `whoami` | `VIEWER` | none |
| `list_sessions` | `VIEWER` | none |
| `get_session_details` | `VIEWER` | `sessionId` |
| `get_session_creation_instructions` | `VIEWER` | none |
| `list_plugins` | `VIEWER` | none |
| `get_plugin_details` | `VIEWER` | `type` |
| `get_plugin_creation_guide` | `VIEWER` | optional `templateType` |
| `create_session` | `ADMIN` | `config` |
| `delete_session` | `ADMIN` | `sessionId`, optional `force` |
| `delete_workflow` | `ADMIN` | `sessionId`, `workflowId`, optional `force` |
| `get_workflow_details` | `VIEWER` | `sessionId`, `workflowId` |
| `start_workflow` | `OPERATOR` | `sessionId`, `workflowId` |
| `get_workflow_status` | `VIEWER` | `sessionId`, `executionId` |
| `get_workflow_history` | `VIEWER` | `sessionId` |
| `get_execution_tree` | `VIEWER` | `sessionId`, `executionId` |
| `get_node_output` | `VIEWER` | `sessionId`, `executionId`, `nodeId`, optional `stream`, `tail`, `head`, `offset`, `limit`, `grep`, `context` |
| `get_execution_logs` | `VIEWER` | `sessionId`, `executionId`, optional `tailLines`, `filterPattern`, `afterCursor` |
| `get_execution_result` | `VIEWER` | `sessionId`, `executionId`, optional `afterCursor` |
| `control_workflow` | `OPERATOR` | `sessionId`, `action`, `executionId`, `workflowId`, `fromNodeId`, optional `reason`, `tree`, `mode` |
| `control_node` | `OPERATOR` | `sessionId`, `executionId`, `nodeId`, `action`, optional `immediate`, `reason` |
| `set_node_breakpoint` | `OPERATOR` | `sessionId`, `executionId`, `nodeId`, `field`, `equalsValue` |
| `signal_node` | `OPERATOR` | `sessionId`, `executionId`, `nodeId`, `signalName`, `decision`, optional `data` |
| `list_pending_approvals` | `VIEWER` | optional `sessionId` |
| `get_scheduler_limits` | `VIEWER` | none |
| `get_metrics_snapshot` | `VIEWER` | optional `scope` |
| `list_supervisors` | `VIEWER` | none |
| `attach_supervisor` | `ADMIN` | `profileId`, `scope`, optional `enabled`, `bindingId` |
| `detach_supervisor` | `ADMIN` | `bindingId` |
| `set_scheduler_limits` | `ADMIN` | `maxConcurrent`, `maxPerWorkflow` |
| `list_workflow_versions` | `VIEWER` | `sessionId`, `workflowId` |
| `diff_workflow_versions` | `VIEWER` | `sessionId`, `workflowId`, `olderVersion`, optional `newerVersion` |
| `rollback_workflow` | `OPERATOR` | `sessionId`, `workflowId`, `version` |
| `note_workflow_version` | `OPERATOR` | `sessionId`, `workflowId`, `version`, `note` |
| `get_control_bus_status` | `VIEWER` | optional `filterType` |

## Ask the user first

Stop and get an explicit yes, naming what will happen, before any of these:

- `create_session` when the `sessionId` already exists (check `list_sessions`): it **replaces** that session. A new session is also code that runs
  on the server, so show the user the workflows you are about to install.
- `delete_session` and `delete_workflow`, and any call with `force` set (it cancels active runs).
- `control_workflow` with `STOP_ALL` (stops every active run of a workflow), and any stop with `mode` `KILL` or `CANCEL`.
- `rollback_workflow` (replaces the current definition) and `set_scheduler_limits` (changes the server for everyone).
- `signal_node` with `decision` `approve` or `reject`, and anything else that answers an approval: that is the user's decision, never yours.
  Show what waits (`list_pending_approvals`) and let the user choose.

Never put credentials, keys or tokens in a workflow you create or in a message; ask the user how the server keeps secrets.

## Create a session

1. `get_session_creation_instructions` (naming rules, the configuration structure, an example) and `list_plugins`; `get_plugin_details` with a node
   `type` for the options and output ports of one plugin.
2. Write `config`: `sessionId`, `name`, `description`, `initiator`, `projectPath`, `tags` and `workflows`, each workflow with `nodes` and `edges`.
   A workflow starts at a trigger node and ends at a terminal node.
   `start_workflow` sends an empty payload, so a workflow that needs input must hold it itself (a node that produces a fixed value, or a mapping
   with literals) unless the user will start it another way. Read `get_plugin_details` for a node type before you write its options: a guessed
   option of the wrong type only fails when the run reaches the node.
3. `list_sessions` to see whether the `sessionId` exists; ask the user if it does (see above). Then `create_session`.
4. Read the answer: `success`, `createdWorkflows` and `warnings`. Act on the warnings before you start a run.

## Start a run and watch it

1. `start_workflow` with `sessionId` and `workflowId` returns `executionId`. It has no payload argument: the run starts with an empty trigger payload.
2. Poll `get_workflow_status`: the run status and a status for each node. Poll every few seconds, not in a tight loop.
3. While it runs, read the log with `get_execution_logs`: pass the previous call's `nextCursor` as `afterCursor` to receive only new lines. Use
   `filterPattern` (a regular expression, at most 200 characters) and `tailLines` to keep the answer small.
4. When it has ended, `get_execution_result` gives what reached the terminal nodes (`complete` is `true` once the run ended).

## Debug a failed run

1. `get_workflow_status`: which node failed or timed out, and the run status.
2. `get_node_output` for that `nodeId`: `stream` `stderr`, `tail`, or `grep` with `context`; it is numbered and returns `nextOffset` for more.
3. `get_execution_logs` with a `filterPattern` such as `ERROR|Exception` for the surrounding lines.
4. `get_workflow_details` for the definition of the node and its edges; `get_execution_tree` when the run started sub-workflows (the failure may
   be in a child run); `get_workflow_history` for earlier runs of the same workflow.
5. If a definition changed lately: `list_workflow_versions`, then `diff_workflow_versions` with `olderVersion` to see what changed.
6. Explain the root cause to the user. Fix the definition (`create_session` replaces the session: ask first) or retry, see below.

## Steer a run

| Want | Call |
|---|---|
| Pause, resume or stop one run | `control_workflow` with `action` `PAUSE`, `RESUME` or `STOP` and the `executionId` (`tree` also covers its sub-workflow runs) |
| Start the run again | `control_workflow` with `RESTART`, or `RESTART_FROM_NODE` with `fromNodeId`; `RECOVER` continues a run a server restart aborted. The new run's id is in `resultExecutionIds` |
| Retry from a node that failed | `control_node` with `action` `RETRY` and the `nodeId`; the new run's id is in `resultExecutionIds` |
| Pause, step or skip one node | `control_node` with `PAUSE`, `RESUME`, `STOP`, `STEP_ENABLE`, `STEP_DISABLE`, `STEP`, `SKIP`, `UNSKIP` or `BREAKPOINT_CLEAR` |
| Hold a node when a field has a value | `set_node_breakpoint` with `field` (such as `payload.status`) and `equalsValue` |
| See what waits for an answer | `list_pending_approvals` (leave `sessionId` out for all of them); answering is the user's call (`signal_node`) |
| Look at or go back to an older definition | `list_workflow_versions`, `diff_workflow_versions`, and (ask first) `rollback_workflow`; `note_workflow_version` labels one |
| Server load | `get_scheduler_limits`, `get_control_bus_status`; `set_scheduler_limits` only after asking |

After a restart, follow the **new** run id with `get_workflow_status`; the old run keeps its record.

## Errors

A failed call comes back as an error result with a message that names the next tool to use (`Session not found: <id>. Use list_sessions ...`,
`Execution not found: <id>. Use get_workflow_history ...`, `executionId is required for action PAUSE.`). Read it and do what it says. HTTP 403
means your role is too low (check `whoami`), 413 means the request was over 1 MB.

Some clients report `output validation failed` with `null found, string expected` for a call that did run (seen with `delete_session`, and
with `whoami`, `get_plugin_details`, `get_node_output` and `get_workflow_details`, where the answer is lost). Do not repeat a destructive call
because of it: check the effect with a read tool first (`list_sessions` after a delete), and tell the user what you saw.

## Connect

Add the server with its URL and, when it has a key, an `Authorization: Bearer <key>` header. The key is the user's: ask them to set it in the
client's own configuration, never paste it into a chat or a file in a repository. The address is `http://localhost:9858/mcp` for a local server.
The page `docs/interfaces/mcp.mdx` of the Yukta repository (docs site: Interfaces, MCP server) has the commands for each client.
