# Agent console: one window for every AI agent of a run

Plan for running any terminal-based AI agent (Claude Code, Codex, Antigravity, pi, or a program nobody has written a preset for) as a workflow node, and for watching and talking to all agents of a run in **one place**: a tab on the workflow page, one CLI command, and the same operations over REST, MCP and for supervisors.

Status (2026-11-15): **accepted, phase AC0 done except the artboard review**, nothing else built. The "today" statements were read from `origin/main` at a9c6a2864 and re-checked at c10778fe0 in AC0; each names the class it comes from. D1 to D8 were decided by Arun in two rounds of questions on 2026-11-15; D9 to D20 and Q1 to Q4 were settled with Arun on the same day in [ADR 0004](../decisions/0004-agent-console.md), which also holds the findings of the code check (F1 to F5), the recorded sessions (F6 to F8) and the library choice. **Open from AC0:** the maintainer's review of the artboards (ADR, "Artboards"; blocks AC7a to AC7c). The work is filed as epic #2363; see [Issues](#issues).

## The problem

A workflow can chain agent nodes: node 1 Claude Code with one model, node 2 Claude Code with another, node 3 Codex, node 4 Antigravity, node 5 pi. Each of these programs has its own terminal UI, so following the run and answering the agents means one window per agent. Yukta already runs two of them headless (`AGENT_SESSION`), but a person can only read their lines in the log and answer approval requests; nobody can send a message to a running agent, and an agent with no preset cannot run at all.

## Decisions

### Decided by Arun (2026-11-15)

| # | Question | Decision |
|---|---|---|
| D1 | How does Yukta talk to "any" agent? | **Three tiers behind one conversation model.** (1) `acp`: the Agent Client Protocol, JSON-RPC over stdio. (2) `jsonl`: today's JSON-lines presets. (3) `pty`: a pseudo-terminal that mirrors the program's own TUI. Structured where the agent allows it, a raw terminal where it does not. |
| D2 | What can a client do to a running agent? | **Steer and chat.** Send a message, interrupt the turn, answer its requests, end it. A node may stay open after the agent finishes (`mode: CONVERSATION`) until someone ends it or it idles out. The default stays: the node ends when the agent finishes. |
| D3 | What hosts the pseudo-terminal? | **A hidden `yukta-cli pty-host` subcommand.** The Go CLI opens the PTY (ConPTY on Windows), keeps a virtual screen and talks to the server over stdin and stdout. No JNI in the native image. |
| D4 | Transport to the clients | **SSE for output, POST for input**, as logs and events do today. No WebSocket. |
| D5 | Place in the web UI | **An Agents tab in the dock** of the workflow page, next to Logs: one timeline for all agent nodes of the run and its child runs. |
| D6 | The CLI window | **`yukta-cli attach <execution>`**: line mode (a streaming timeline with a prompt line), and raw pass-through for a `pty` agent. No full-screen TUI. |
| D7 | Automated clients (supervisors, MCP, REST keys) | **The same operations as a person, by role, audited.** A node limits who may steer with `steerAudience`. |
| D8 | Relation to `AGENT_SESSION` | **Evolve it.** Same type and ports; it moves to its own module and gains the protocol tiers and the conversation features. Breaking changes are accepted (alpha). |

### Proposed by this plan, accepted in ADR 0004 (2026-11-15)

| # | Question | Decision (as proposed, additions in bold) | Why |
|---|---|---|---|
| D9 | What is stored? | **An `AgentConversation` record and an append-only list of `AgentEvent`s** with one change sequence per store | One model for three protocols; `Last-Event-ID` catch-up; a merged per-run view is a filter over one sequence |
| D10 | How do message, interrupt and end reach the agent? | **As control commands** (`AgentMessageCommand`, `AgentInterruptCommand`, `AgentEndCommand`) on the path `AnswerInputCommand` takes. Terminal keystrokes go directly, with the same authorisation | Review, arbiter, approval of operations and audit apply without new code. Keystrokes are too many and too small for that pipeline |
| D11 | A message arrives while the agent works | **Queued until the turn ends**, unless the harness can take it mid-turn (`steer`). `interrupt: true` cancels the turn first | ACP has no mid-turn message; the rule must be the same for every harness |
| D12 | ACP permission options | **Only approve and reject**: approve picks the first `allow_once` option (else `allow_always`), reject the first `reject_once` (else `reject_always`) | `InputKind.APPROVAL` has two decisions; "always allow" is a policy decision that belongs in the node's `args`, not in a click |
| D13 | ACP client capabilities (`fs`, `terminal`) | **None declared.** The agent uses its own file and shell tools | Otherwise Yukta would have to implement file access and terminals for the agent, with their own security surface |
| D14 | Timeouts of a conversation | `timeout` per turn (exists), `idleTimeout` per wait for the next message (default 900 s), `conversationTimeout` for the whole node (default 14400 s) | The engine needs one number per node (`Plugin.getDefaultTimeout(config)`); a chat has three different clocks |
| D15 | Is the terminal tier on by default? | **No.** `yukta.agents.pty.enabled=false`; structured tiers need no switch beyond enabling the plugin | A terminal accepts any keystroke; nothing can review what is typed |
| D16 | The run log | **Unchanged.** Agent text and tool names keep going to the log as `agent` lines | `yukta-cli logs`, the Logs panel, `get_execution_logs` and the heal tooling keep working |
| D17 | Name of the resource | **Conversation**: REST `/api/conversations`, CLI `conversations` | `yukta-cli agents` exists (it installs Yukta into agent CLIs) and `/api/agent` is the AI-agent API (`AgentApiController`) |
| D18 | New UI dependencies | **Only `@xterm/xterm` and `@xterm/addon-fit`**, loaded on demand. Messages are plain text (`whitespace-pre-wrap`), no Markdown renderer | The timeline needs no library (`@tanstack/react-virtual` and `tabs.tsx` exist); a terminal emulator is not something to write |
| D19 | Agent nodes on remote workers | **Not in this epic.** A conversation runs on the control plane | The worker stream (`NodeFrame`: `START`, `MESSAGE`, `HEARTBEAT`, `COMPLETE`, `ERROR`) carries no gateway calls today (confirmed in AC0, ADR F3); see [Not in this plan](#not-in-this-plan) |
| D20 | Agent questions that are not approvals | **Presets may map a request to `CHOICE` or `TEXT`** in a later phase; v1 maps every request to `APPROVAL` | Only the approval shape is recorded from a real run today |

## Principles

1. **One conversation model, many protocols.** Clients never see which protocol an agent speaks; they see messages, tools, requests, a state and, for `pty`, a terminal.
2. **A conversation is a record, a client is a view.** The Agents tab, `attach`, MCP and a supervisor read the same stored events and send the same commands.
3. **Requests keep one path.** What an agent asks goes through the input request system (`InputGateway`), so routes, tiers, supervisors, deadlines and the inbox of epic #2348 apply. The transcript only points at the request.
4. **Adding an agent is a JSON file.** Java changes only for a new protocol.
5. **Nothing reaches someone who may not see the run**, and nobody steers an agent the node did not allow.
6. **Without the new parts, today's behaviour.** No conversation gateway, or `yukta.agents.enabled=false`: `AGENT_SESSION` runs as it does now.

## What exists today

| Piece | State (class) |
|---|---|
| The plugin | `AGENT_SESSION` (`AgentSessionPlugin`, module `plugins/processors/process-executor`, package `com.infenia.yukta.plugin.process`): EXTENDED tier, restricted, ports `success`, `failure`, `incomplete`, payload `{status, reason, text, sessionId, turn, exitCode}` |
| Presets | JSON files `agent-harnesses/claude-code.json` and `codex.json` (`AgentPreset`, `AgentHarnesses`), more from `yukta.ai.presets-dir`. A preset holds `command`, `modelArgs`, `maxTurnsArgs`, `resumeArgs`, `capabilities` (`stream`, `resume`, `ask`), `output` (how to read JSON lines) and `interactive` (how to converse over stdin) |
| Reading output | `AgentOutput`: a `PluginLogSink` that parses each stdout line, keeps the result line, hands requests to a handler and writes `live` lines to the run log as stream `agent` |
| Approvals | `AgentApprovals`: a request line becomes `InputKind.APPROVAL` through `InputGateway.ask`; the answer is written to stdin with the preset's `allow` or `deny` template |
| Starting processes | `ProcessExecutorGateway.executeForResult(spec, sink)` and `executeInteractive(spec, sink, onStart)` with `StdinWriter.write(line)` and `close()`. Line based in both directions. Allow-list `yukta.process.allowed-commands`; a clean environment with `yukta.process.pass-env` (#2327); cancel kills the process tree |
| Sessions | `AgentSessionStore` (`InMemoryAgentSessionStore`): one turn at a time per `session` key, `onBusy` WAIT or FAIL, the resume token |
| Logs | `PluginLogEntry` in `PluginLogStore`; SSE `GET /api/sessions/{s}/executions/{e}/logs` and `/logs/entries` (`LogManagementController`, history then live, `SseStreams.events` with `Last-Event-ID`) |
| Events | `ExecutionEvent` (sealed; `LogLine`, `NodeStatusChanged`, the five input events) on a best-effort feed |
| Commands | `ExecutionControlCommand` (sealed); `AnswerInputCommand` is handled by `AnswerInputCommandProcessor`; `NodeCommands`, `DefaultControlBusGateway`, `CommandHandler`, `ParkedCommands` and `DirectiveDispatcher` name every command |
| Identity | `ApiRole` (`VIEWER`, `OPERATOR`, `ADMIN`), `ApiKeys.Match(name, role, sessions, agent)`, `ExecutionAccess`, `AuditSink`, `CallRateLimiter` |
| UI | `WorkflowDetailsPage` renders `LogsPanel` (the dock, line 463) and `InputsPanel`; `useExecutionLogs` reads the entries stream with `useEventSourceJson`; `InputRow` answers a request |
| CLI | Go, cobra only. `logs`, `inputs list|show|answer|escalate`, `controlbus watch`; `client.StreamExecutionLogEntries`, `client.StreamEvents`. The group `agents` installs Yukta into agent CLIs |
| MCP | `get_execution_logs`, `get_node_output`, `list_pending_inputs`, `answer_input`, `control_node`, `list_ai_backends`, `get_plugin_details` |
| WebSocket | none in the server, the UI or the CLI |

Checked on this machine on 2026-11-15: `claude` 2.1.325 has `--input-format stream-json`, `--output-format stream-json`, `--permission-prompt-tool`, `--resume`, `--session-id`, `--include-partial-messages`; `agy` 1.5.8 (installed at `~/.gemini/bin/agy`, not on `PATH`) has the subcommand `acp`, `-p --input-format stream-json --output-format stream-json`, `--mode`, `--continue`. `codex` and `pi` are not installed here. Google's Gemini CLI is not supported (D21).

Claude Code was run and recorded in AC0 (ADR F6); `agy acp` was run with a prompt, a permission request and a cancel (ADR F8). The rest is from documentation and was not run; each is recorded in the phase that ships its preset (Codex and pi in AC11):

| Agent | Structured mode | ACP |
|---|---|---|
| Claude Code | `claude -p --input-format stream-json --output-format stream-json` (preset exists) | through the adapter `claude-agent-acp` |
| Codex | `codex exec --json` (preset exists, never run against the real CLI) | through the adapter `codex-acp` |
| Antigravity | `agy -p ... --output-format stream-json`, `--input-format stream-json` (not run; an open report says stdout stays empty without a TTY). Not shipped: the ACP mode below covers it | **native: `agy acp`, recorded in AC0** |
| pi | `pi --mode rpc`: JSON lines on stdin and stdout, commands carry an `id` | through the adapter `pi-acp` |

What is missing: a transcript that is more than log lines; any way to send a message, interrupt or end an agent; a node that stays open for a chat; ACP; a terminal for agents with no structured mode; one view across the agent nodes of a run in the UI and the CLI; MCP tools and supervisor commands for the same.

## Architecture

```text
 UI Agents tab     yukta-cli attach      MCP tools        supervisor
      |  SSE + POST     |  SSE + POST        |               | events + commands
      v                 v                    v               v
 web: ConversationsController          mcp: ConversationTools   control pipeline
      \____________________________   ________________________/
                                   \ /
 core: AgentConversationService  (store, change sequence, redaction, limits)
       AgentSteering             (authorise, route a command to the live conversation)
       DefaultAgentConversationGateway  <-- plugin-api: AgentConversationGateway
                                    ^
                                    | open(), emit(), state(), commands(), end()
 plugin agent-session: AgentSessionPlugin
       AgentAdapter:  JsonlAdapter | AcpAdapter | PtyAdapter
                                    |
 plugin process-executor: ProcessExecutorGateway.executeInteractive
                                    |
            claude / codex / agy acp / pi --mode rpc / yukta-cli pty-host -- <any program>
```

- The plugin opens a conversation for each message it works on, writes what the agent does as events, and listens for commands. It knows nothing about clients.
- Core stores the events, gives each a sequence number, serves history and a live stream, and decides whether a caller may send a command.
- Requests (approvals) go through `InputGateway` as today; the adapter also writes a `REQUEST` event that points at the input request.
- `process-executor` stays the only place that starts a process.

## Contracts

Names are binding where a client sees them (REST, MCP, CLI, event `kind` and `data` keys, config options). Java signatures are the intended shape; an implementer may adjust them and says so in the PR.

### The record (plugin-api, package `com.infenia.yukta.plugin.agent`)

```java
public enum AgentProtocol { JSONL, ACP, PTY }
public enum AgentState { STARTING, RUNNING, WAITING, IDLE, ENDED }
public enum AgentEventKind { STATE, MESSAGE, TOOL, PLAN, REQUEST, TERMINAL, RESULT, NOTICE }

public record AgentConversation(
    String id,                 // UUID
    String executionId, String rootExecutionId, String sessionId, String workflowId,
    String nodeId, String messageId,
    String harness, AgentProtocol protocol, @Nullable String model,
    Set<String> capabilities,  // see below
    AgentState state, @Nullable String outcome, @Nullable String reason,
    int turn, int queued,      // finished turns; messages waiting for the turn to end
    Audience steerAudience,
    Instant startedAt, Instant updatedAt, @Nullable Instant endedAt,
    long seq) {}               // sequence of its last change

public record AgentEvent(
    long seq, String conversationId, Instant timestamp,
    AgentEventKind kind, @Nullable String itemId, Map<String, Object> data) {}
```

States:

| State | Meaning | Leaves to |
|---|---|---|
| `STARTING` | the process is starting, the protocol handshake runs | `RUNNING`, `ENDED` |
| `RUNNING` | the agent works on a turn | `WAITING`, `IDLE`, `ENDED` |
| `WAITING` | the agent waits for a person or supervisor: an input request is open, or a `pty` screen matches `attentionPattern` | `RUNNING`, `ENDED` |
| `IDLE` | `CONVERSATION` mode only: the turn is over, the agent waits for the next message | `RUNNING`, `ENDED` |
| `ENDED` | terminal; `outcome` is `success`, `failure` or `incomplete` | none |

Capabilities (strings, from the preset and the mode; a command the conversation lacks the capability for is refused):

| Capability | Meaning |
|---|---|
| `message` | accepts a message while the conversation lives |
| `steer` | delivers a message during a turn; without it a message is queued to the end of the turn |
| `interrupt` | can stop the current turn without ending the process |
| `ask` | sends structured requests |
| `resume` | can continue an earlier session of the harness |
| `terminal` | has a terminal: input, resize and screen |

Event data by kind. Events with the same `itemId` describe one item: `MESSAGE` pieces are appended in order, a later `TOOL`, `PLAN` or `REQUEST` event replaces the fields it carries.

| Kind | `itemId` | `data` |
|---|---|---|
| `STATE` | none | `state`, `reason?` |
| `MESSAGE` | message id | `role` (`user`, `agent`, `thought`), `text`, `more` (true when another piece follows), `author?` (who sent a `user` message; `prompt` for the node's prompt), `queued?` |
| `TOOL` | tool call id | `title`, `toolKind?`, `status` (`pending`, `running`, `completed`, `failed`), `input?`, `output?`, `truncated?` |
| `PLAN` | `plan` | `entries`: list of `{text, status}` |
| `REQUEST` | input request id | `inputRequestId`, `inputKind`, `prompt`, `state` (`open`, `answered`, `expired`, `cancelled`), `decision?`, `by?` |
| `TERMINAL` | none | output: `data` (base64), `cols`, `rows`; or a screen: `screen` (text), `cols`, `rows`, `cursorRow`, `cursorCol`, `settled: true` |
| `RESULT` | none | `status`, `reason`, `text`, `turn` |
| `NOTICE` | none | `level` (`INFO`, `WARN`, `ERROR`), `text` (stderr lines, protocol errors, "message queued") |

Limits, applied by core before an event is stored (settings below): `text` up to 64 KB per event; `input` and `output` of a tool up to 4 KB each (`truncated: true` beyond); a terminal chunk up to 16 KB. `text`, `title`, `input`, `output` and `prompt` pass `InputRedactor`.

### The gateway (plugin-api)

```java
public interface AgentConversationGateway {
  Mono<AgentConversationHandle> open(AgentConversationSpec spec);
}

public record AgentConversationSpec(
    InputIdentity who, String messageId, String harness, AgentProtocol protocol,
    @Nullable String model, Set<String> capabilities, Audience steerAudience) {}

public interface AgentConversationHandle {
  String id();
  void emit(AgentEventKind kind, @Nullable String itemId, Map<String, Object> data);
  void state(AgentState state, @Nullable String reason);
  void queued(int count);
  Flux<AgentCommand> commands();                 // completes when the conversation ends
  void end(String outcome, @Nullable String reason);   // idempotent
}

public sealed interface AgentCommand {
  record Message(String text, boolean interrupt, String actor) implements AgentCommand {}
  record Interrupt(String actor) implements AgentCommand {}
  record End(@Nullable String outcome, @Nullable String reason, String actor) implements AgentCommand {}
  record TerminalInput(byte[] data, String actor) implements AgentCommand {}
  record TerminalResize(int cols, int rows) implements AgentCommand {}
}
```

The plugin takes the gateway as `ObjectProvider<AgentConversationGateway>`, as it takes `InputGateway`. When there is none it uses a handle that drops events and never emits a command.

### Core (package `com.infenia.yukta.service.agent`)

| Class | Job |
|---|---|
| `AgentConversationStore` | `save(conversation)`, `append(event)` (assigns `seq`), `find(id)`, `list(filter)`, `events(filter, afterSeq, limit)`, `latestScreen(id)`, `purge(before)`, `epoch()` |
| `InMemoryAgentConversationStore` | the default. Bounded: ended conversations go first, oldest first; a live one is never evicted. Terminal output chunks are kept in a ring of bytes per conversation and do not count as events; the latest screen is kept apart |
| `AgentConversationService` | opens, appends (limits and redaction), changes state, ends; one change sequence that every create, change and event advances; a live stream of changes |
| `DefaultAgentConversationGateway` | the plugin's side: resolves `rootExecutionId` from the execution tree, builds the handle |
| `AgentSteering` | checks who may send a command and hands it to the live handle; refuses when the conversation is not live or lacks the capability |
| `AgentRetention` | removes ended conversations after `retention` (leader only, like `InputRetention`) |
| `YuktaProperties.Agents` | the settings below, validated at start |

The live stream: a multicast sink with a bounded buffer per subscriber (1024). A subscriber that falls behind gets an error and reconnects with its last sequence number; the store has the history. Reading is "history, then live" with the seam `LogManagementController.historyThenLive` uses.

`AgentSteering` allows a command when all of these hold: the caller reaches the session (`ApiKeys.Match.reaches`), has role `OPERATOR` or higher, and matches a selector of the conversation's `steerAudience`: `role:OPERATOR` (an operator or admin), `role:ADMIN`, `key:<name>`, `supervisor:<id>` or `supervisor:*`. A key marked `agent` matches only a selector that names it. The existing matching of `DefaultAnswerAuthorizer` is private, exact and does not treat an `agent` key specially (ADR F1): AC4 extracts it into a shared `AudienceMatcher`, keeps its grammar (`role:OPERATOR`, `role:ADMIN`, `key:<name>`, `supervisor:<id>`, `supervisor:*`; no `role:VIEWER` and no "at or below" rule) and adds the `agent` restriction in `AgentSteering`.

Settings (`yukta.agents.*`):

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | false: no conversations are recorded, the plugin runs as today |
| `retention` | `7d` | how long an ended conversation is kept |
| `max-conversations` | `500` | in-memory store |
| `max-events-per-conversation` | `5000` | oldest events of a conversation go first; a `NOTICE` says so once |
| `max-message-bytes` | `65536` | per event text, and per message sent to an agent |
| `max-tool-bytes` | `4096` | per tool input and output |
| `terminal-ring-bytes` | `262144` | terminal output kept per conversation |
| `messages-per-minute` | `30` | per caller and conversation |
| `pty.enabled` | `false` | the terminal tier |
| `pty.host` | `yukta-cli` | the executable that provides `pty-host` |
| `pty.cols`, `pty.rows` | `120`, `40` | the terminal size until a client resizes |

`yukta.ai.presets-dir` stays as it is.

### Commands (plugin-api, `ExecutionControlCommand`)

Three new permitted records, handled like `AnswerInputCommand` (follow that command through `NodeCommands`, `DefaultControlBusGateway`, `CommandHandler`, `ParkedCommands`, `DirectiveDispatcher` and `AnswerInputCommandProcessor`, and add the same for each):

```java
record AgentMessageCommand(String executionId, String nodeId, String conversationId, String text, boolean interrupt)
record AgentInterruptCommand(String executionId, String nodeId, String conversationId)
record AgentEndCommand(String executionId, String nodeId, String conversationId, @Nullable String outcome, @Nullable String reason)
```

`AgentCommandProcessor` (core, `service.control.processor`) calls `AgentSteering`. Terminal input and resize are not control commands: the controller calls `AgentSteering` directly, with the same authorisation, and writes one audit record per caller and conversation per minute ("terminal input, N bytes").

### The plugin (`AGENT_SESSION`)

Module `plugins/processors/agent-session`, package `com.infenia.yukta.plugin.agentsession`. It depends on `process-executor` for `ProcessExecutorGateway`; add `"agent-session>process-executor"` to `ModuleRules.ALLOWED` (decision D8). `ProcessExecutorAutoConfiguration` loses the agent beans; the new module has `AgentSessionAutoConfiguration`.

New and changed options of a node (existing ones stay):

| Option | Values | Default | Meaning |
|---|---|---|---|
| `mode` | `TASK`, `CONVERSATION` | `TASK` | `TASK`: the node ends when the agent finishes its turn. `CONVERSATION`: the node stays open (`IDLE`) for further messages until `End`, `idleTimeout` or `conversationTimeout` |
| `idleTimeout` | seconds | `900` | `CONVERSATION`: how long `IDLE` may last. On expiry the node ends with the last turn's status (`incomplete` when no turn finished) |
| `conversationTimeout` | seconds | `14400` | `CONVERSATION`: the cap for the whole node; this is what `getDefaultTimeout(config)` returns in that mode |
| `steerAudience` | selectors, as `approvalAudience` | `["role:OPERATOR"]` | who may send a message, interrupt, end, or type into the terminal |
| `command` | list of strings | none | only with the harness `terminal`: the program to run in the terminal |
| `terminalSize` | `{cols, rows}` | from settings | only for a `pty` harness |

The payload gains `conversationId`. In `CONVERSATION` mode the port comes from the last turn's result; `End` with an `outcome` overrides it. While `IDLE` the adapter runs the heartbeat of the Reactor context (`WorkflowContext.HEARTBEAT_KEY`) every 10 s.

`validateConfig` refuses, with a message that names the harness and the missing capability: `mode: CONVERSATION` with a harness that has neither a conversation over stdin (`interactive`, ACP, pty) nor `resume`; `approvals: ASK` without `ask`; `command` with a harness other than `terminal`; a `pty` harness while `yukta.agents.pty.enabled` is false.

Preset file, version 2. `protocol` is new; a file without it is `jsonl`, so today's files load unchanged.

```json
{
  "id": "antigravity",
  "protocol": "acp",
  "description": "Antigravity CLI over the Agent Client Protocol.",
  "command": ["agy", "acp"],
  "capabilities": { "ask": true, "interrupt": true, "resume": true },
  "acp": { "authMethod": null }
}
```

```json
{
  "id": "terminal",
  "protocol": "pty",
  "description": "Any program in a terminal. The node gives the command.",
  "command": [],
  "pty": {
    "type": "{prompt}\r", "readyPattern": null, "readyDelayMs": 1500,
    "submit": "\r", "interruptKeys": "\u0003",
    "attentionPattern": null, "donePattern": null
  }
}
```

Additions to `interactive` of a `jsonl` preset: `steer` (boolean: a user message may be written during a turn) and `interrupt` (a JSON template written to stdin to stop the turn; `{id}` is a new request id). `capabilities` of a conversation are derived: `message` when the preset has `interactive`, is `acp` or `pty`, or has `resume` and the mode is `CONVERSATION`; `steer` and `interrupt` from the fields above (`acp`: `interrupt` always, `steer` never; `pty`: both).

Inside the module, one interface and three implementations (`adapter` package):

```java
interface AgentAdapter {
  AgentProtocol protocol();
  Mono<Map<String, Object>> run(AgentRun run);   // the outcome map of today
}
```

`AgentRun` carries the message, the preset, the process spec, the prompt, the resume token, the approval settings, the mode and timeouts, the conversation handle, the log sink, the identity and the heartbeat. Shared helpers: `Mailbox` (queues messages during a turn, reports `queued`), `AgentRequests` (today's `AgentApprovals`, plus the `REQUEST` events), `LogMirror` (writes agent text and tool titles to the run log as stream `agent`, as today).

#### `JsonlAdapter` (today's `AgentTurn` and `AgentOutput`)

- A preset with `interactive` always starts with the interactive command when conversations are on, so a message can be sent. With `approvals: NONE` a request line is answered with the `deny` template and the text "approvals are off for this node". A preset without `interactive` runs one-shot as today and has no `message` capability in `TASK` mode.
- Each `live` rule match becomes a `MESSAGE` (role `agent`) or, for a rule with a `prefix`, a `TOOL` event; a new optional field `kind` on a `live` rule (`message`, `thought`, `tool`) says which, default by prefix as described.
- A message: written with `userMessage` at once when `steer` is true, else queued by `Mailbox` and written when the result line arrives. `interrupt: true` writes the `interrupt` template first.
- The result line: `RESULT` event. `TASK`: close stdin, end. `CONVERSATION`: state `IDLE`, wait for a message, `End` or `idleTimeout`.
- A one-shot preset with `resume` in `CONVERSATION` mode: each message starts the command again with `resumeArgs`.
- Recorded in AC0 (ADR F6, `claude` 2.1.325): a user message written during a turn is **taken inside that turn** (one `result`, which includes it), so the `claude-code` preset declares `steer: true` and no queue is needed for it. The interrupt line is `{"type":"control_request","request_id":"<id>","request":{"subtype":"interrupt"}}`; the answer is a `control_response` with `response.still_queued` (the messages not yet taken), then the turn ends with a `result` of `subtype: error_during_execution`, `terminal_reason: aborted_tools`. The process survives and takes the next message. After our own interrupt the adapter reads that `result` as a cancelled turn, not a failure, and does not read the outcome from the exit code.

#### `AcpAdapter`

JSON-RPC 2.0, one message per line on stdin and stdout, through `executeInteractive`. `AcpWire` frames, numbers requests and matches responses. Every method and field name below was checked in AC0 against the published schema `schema-v1.32.0` (ADR F7); a session was recorded against the real `agy acp` 1.5.8 (ADR F8). The flow:

1. `initialize` with `protocolVersion: 1`, `clientCapabilities: {fs: {readTextFile: false, writeTextFile: false}, terminal: false}`. An unsupported version in the answer fails the node.
2. `authenticate` when the preset names an `authMethod`.
3. `session/load` when there is a resume token and the agent declared `loadSession`; else `session/new` with `cwd` (the absolute `workingDir`) and `mcpServers: []`. The session id is the payload's `sessionId` and the resume token.
4. `session/prompt` with the prompt as one text block.
5. Notifications `session/update`: `agent_message_chunk` to `MESSAGE` (`agent`), `agent_thought_chunk` to `MESSAGE` (`thought`), `tool_call` and `tool_call_update` to `TOOL`, `plan` to `PLAN`; others are ignored. Chunks are gathered for at most 200 ms into one piece.
6. Request `session/request_permission`: with `approvals: ASK` an `APPROVAL` input request (the spec `AgentApprovals` builds today); the answer selects an option as in D12. With `approvals: NONE`, a timeout or any failure: the reject option. When the run is cancelled: outcome `cancelled`.
7. Any other request from the agent (`fs/*`, `terminal/*`): JSON-RPC error `-32601`.
8. The answer to `session/prompt` ends the turn: `end_turn` is `success`; `max_tokens` and `max_turn_requests` are `incomplete`; `refusal` is `failure`; `cancelled` (after an interrupt) sends the next queued message, or goes `IDLE` (`CONVERSATION`) or ends `incomplete` (`TASK`).
9. Interrupt: the notification `session/cancel`; open permission requests are answered `cancelled`.
10. A message during a turn is queued; `interrupt: true` cancels first.
11. End: close stdin; a process still alive after 5 s is killed by cancelling the gateway subscription.

The payload's `text` is the agent text of the last turn, up to 64 KB.

#### `PtyAdapter` and `yukta-cli pty-host`

The server starts `[pty.host, "pty-host", "--cols", C, "--rows", R, "--"] + command`. `ProcessExecutionSpec` gains `launcher` (a prefix only code can set, never a workflow): the gateway checks the allow-list against `command` and runs `launcher + command`, so an operator allows `agy`, not `yukta-cli`.

Frames, one JSON object per line:

| Direction | Frame |
|---|---|
| server to host | `{"t":"in","d":"<base64>"}`, `{"t":"resize","cols":120,"rows":40}`, `{"t":"close"}` |
| host to server | `{"t":"out","d":"<base64>"}`, `{"t":"screen","cols":..,"rows":..,"text":"..","cursorRow":..,"cursorCol":..}`, `{"t":"exit","code":0}` |

The host (`cli/internal/ptyhost`, hidden command `pty-host`): opens the PTY with `TERM=xterm-256color`; gathers output into frames of at most 16 KB or 30 ms; feeds a virtual screen and sends a `screen` frame when the screen has been quiet for 400 ms and differs from the last one sent; on `close` or end of stdin sends SIGHUP, then SIGKILL after 5 s; exits with the child's code after the `exit` frame. Libraries (decided in AC0, ADR "Libraries for `pty-host`"; spiked, and all modules MIT or BSD-3-Clause): `github.com/aymanbagabas/go-pty` (PTY and ConPTY in one) and `github.com/charmbracelet/x/vt` (the screen), behind a small `screen` interface so `github.com/hinshun/vt10x` can replace it; AC6a measures the binary size and the wide-character case first.

The adapter: `out` to `TERMINAL` output events; `screen` to a `TERMINAL` screen event (only the latest is kept). The prompt is an argument when `command` contains `{prompt}`, else `pty.type` is typed once a screen matches `readyPattern` (or after `readyDelayMs`). A message is typed, followed by `submit`. Interrupt types `interruptKeys`. A screen that matches `attentionPattern` sets `WAITING` until the screen changes. The node ends when the process exits (code 0 is `success`, else `failure`), when a screen matches `donePattern` (`success`), or on `End` (the given outcome, else `success`). The payload's `text` is the last screen without trailing blank lines. The run log gets one line when the terminal starts and one when it ends, not the bytes.

### REST (web, `ConversationsController`)

| Method and path | Role | Result |
|---|---|---|
| `GET /api/conversations?state=&sessionId=&executionId=&tree=true&limit=` | `VIEWER` | conversations the caller reaches, newest first |
| `GET /api/executions/{executionId}/conversations?tree=true` | `VIEWER` | those of a run and, with `tree`, of its child runs |
| `GET /api/conversations/{id}` | `VIEWER` | one conversation |
| `GET /api/conversations/{id}/events?after=&limit=&kinds=` | `VIEWER` | a page of events, `limit` up to 500 |
| `GET /api/executions/{executionId}/conversations/stream?tree=true&conversationId=&kinds=&terminal=true` | `VIEWER` | SSE, below |
| `POST /api/conversations/{id}/messages` `{text, interrupt}` | steer | 202 `{queued}` |
| `POST /api/conversations/{id}/interrupt` | steer | 202 |
| `POST /api/conversations/{id}/end` `{outcome?, reason?}` | steer | 202 |
| `POST /api/conversations/{id}/terminal/input` `{data}` (base64, up to 8 KB) | steer | 204 |
| `POST /api/conversations/{id}/terminal/resize` `{cols, rows}` | steer | 204 |
| `GET /api/conversations/{id}/terminal/screen` | `VIEWER` | `{cols, rows, text, cursorRow, cursorCol}` |

Requests of an agent are answered on the existing `POST /api/executions/{executionId}/inputs/{requestId}/answer`.

The stream: event `conversation` carries an `AgentConversation` (on connect one per conversation, then on every change); event `event` carries an `AgentEvent`. The SSE id is `seq`; `Last-Event-ID` resumes after it. On connect, for each live `pty` conversation the latest screen is sent as a `TERMINAL` screen event before live output; a terminal view resets itself with it. `terminal=false` leaves out terminal output and sends screens only. The stream stays open while the root run is not terminal, with the heartbeat of `SseStreams`.

Errors: 404 unknown or not reachable; 403 role or `steerAudience`; 409 `{code: "not_live"}` or `{code: "unsupported", capability}`; 413 too large; 429 over `messages-per-minute`. `docs/api/openapi.json` and `ui/openapi.json` are regenerated with the change.

### MCP (`mcp`, `ConversationTools`)

| Tool | Does |
|---|---|
| `list_agent_conversations` | by execution (tree) or state: id, node, harness, model, state, turn, open request ids |
| `get_agent_conversation` | the conversation and its events folded into items, text only, `afterSeq` and `limit` for paging; agent text passes `LogSanitizer` |
| `send_agent_message` | a message, optional `interrupt` |
| `interrupt_agent` | stops the turn |
| `end_agent_conversation` | ends it, optional `outcome` and `reason` |
| `read_agent_screen` | the latest screen of a `pty` conversation as text |
| `send_agent_keys` | types text into a `pty` conversation; `keys` names special keys (`enter`, `esc`, `tab`, `up`, `down`, `ctrl-c`) |

`answer_input` stays the way to answer a request. The read-only fixer key can call the two read tools.

### CLI (`cli`)

```text
yukta-cli conversations list [--execution E] [--state RUNNING,WAITING,IDLE]
yukta-cli conversations show <id> [--after SEQ] [--json]
yukta-cli conversations send <id> <text> [--interrupt]
yukta-cli conversations interrupt <id>
yukta-cli conversations end <id> [--outcome success|failure] [--reason R]
yukta-cli attach <execution-id> [--agent <id|node-id>] [--json]
```

`attach` follows the stream of the run tree. With a terminal on stdin and stdout it prints the timeline above a prompt line (`golang.org/x/term`, `term.NewTerminal`, which redraws the prompt when output arrives); one line per item, prefixed `[node harness]`; control sequences are removed from agent text. The prompt sends its line to the selected agent. Commands: `/agents` (list, with a number each), `/agent <number|node>` (select), `/approve [request] [rationale]`, `/reject [request] [rationale]`, `/interrupt`, `/end`, `/term` (for a `pty` agent: raw mode, keys and output pass through, the size follows the window, `Ctrl-]` returns), `/quit`. Without a terminal, or with `--json`, it only prints (one JSON event per line with `--json`) and reads nothing. It reconnects with the last sequence number and ends when the root run does.

### Supervisors

- The feed gets `ExecutionEvent.AgentConversationChanged(conversation, timestamp)` on every state change. Requests already arrive as `InputRequested`. Text is not put on the feed (it is best effort); a supervisor that needs the transcript reads it by REST or MCP.
- A supervisor may issue the three new commands. They pass the pipeline every command passes; `steerAudience` must name it (`supervisor:<id>` or `supervisor:*`).
- The `rules` supervisor gets actions for them and `SupervisorContract` lists them. Actions are declared in four places (ADR F4), and AC10 changes all four: the `switch` in `EventRule.commandFor` (new `then.command` names), the record `RuleSet.Then` (new fields for the text and `interrupt`), `SupervisorCapabilities.KNOWN_COMMANDS` (listed by hand for the native image; `SupervisorCapabilitiesTest` checks it) and `SupervisorContract` with `docs/reference/supervisors/contract.mdx`.

## Flows

**A run with five agents.** The workflow reaches node 1 (`claude-code`): the plugin opens a conversation, the Agents tab shows it `RUNNING` and its messages and tools as they come. It asks to run a shell command: an input request is raised, the timeline shows it with Approve and Reject, the state is `WAITING`; a supervisor or the person answers; the agent goes on and finishes; the node emits on `success`. Node 2 (`claude-code`, another model) opens the next conversation in the same timeline. Node 3 (`codex`), node 4 (`antigravity`, in a terminal if its headless mode fails without a TTY), node 5 (`pi`) follow. The person never left the tab; `yukta-cli attach <execution>` shows the same in a terminal.

**Steering.** The person types "also update the README" while node 1 works. The harness cannot take it mid-turn: the message is stored with `queued: true`, the conversation shows "1 queued", and it is written when the turn ends. With "Interrupt and send" the turn is cancelled first.

**A chat node.** A node with `mode: CONVERSATION`: after the first answer the state is `IDLE` and the node stays `RUNNING` for the engine. The person asks follow-ups; End closes it and the node emits on the last turn's port.

**A terminal agent.** A node with `harness: terminal` and `command: ["some-agent"]`: the Agents tab shows a terminal; keys go to the program; MCP clients read the screen as text and type with `send_agent_keys`.

## Security

- `AGENT_SESSION` stays restricted and off unless named in `yukta.plugins.enabled`. Every program still passes `yukta.process.allowed-commands`; with `pty-host` the wrapped command is the one checked.
- Processes start with a clean environment (#2327); what an agent needs (its home, its API key) is listed in `yukta.process.pass-env` or the node's `env`. The docs say so, because a missing variable looks like a login failure.
- Reading needs `VIEWER` and reach of the session; steering needs `OPERATOR`, reach and `steerAudience`. An open server (no keys) is `anonymous` with `ADMIN`, as everywhere.
- Every message, interrupt and end is a control command: reviewed, and written to the audit with the actor. Messages carry their author in the transcript.
- The terminal tier is off by default (D15). What is typed cannot be reviewed; input is audited by volume.
- Agent output is untrusted text. The UI renders it as text; the terminal view is an emulator, with clipboard and link handlers off; the CLI removes control sequences in line mode; MCP results pass `LogSanitizer`, so an agent cannot give instructions to the client that reads it unnoticed.
- Events pass `InputRedactor`; sizes and rates are capped (settings above).
- A message sent by an automated client is an instruction to an agent that can act. That is the reason for `steerAudience`: the default names operators, not supervisors and not agent keys.

## UI and UX design

Artboards first (phase AC0, in the design system artifact): the dock with tabs, the Agents tab with one and with several agents, an open request, a queued message, a terminal agent, the empty state, narrow width.

- `WorkflowDetailsPage`: the dock gets tabs **Logs** and **Agents** (`tabs.tsx`); the height key `yukta.logsHeight` is shared, the tab is kept in `yukta.dockTab`. The Agents tab shows a count when a conversation is `WAITING`. A maximise button gives the dock the full column.
- `features/workflow/components/agents/`: `AgentsPanel` (layout), `AgentList` (one row per conversation: node name, harness, model, a state dot; "All" on top), `AgentTimeline` (virtual list, `role="log"`, `aria-live="polite"`), `AgentComposer`, `AgentTerminal` (loaded on demand). `hooks/useAgentStream.ts` reads the stream with `useEventSourceJson` and folds events into items; `api/conversations.ts`.
- Items: a message (who, text as `whitespace-pre-wrap`), a tool (one line, expandable input and output), a plan, a request (the existing `InputRow`, answered in place), a result (status, reason), a notice. In "All" each item has a chip with its node.
- The composer sends to the selected conversation: Enter sends, Shift+Enter breaks the line. It is disabled, with the reason, when the conversation is ended, lacks `message`, or the caller may not steer. Buttons: Interrupt (with `interrupt`), End. While the agent works, the send button offers "Queue" and "Interrupt and send".
- A node on the canvas with a live conversation shows its state; selecting it selects the conversation.
- A `pty` conversation shows `AgentTerminal` (xterm.js with the fit addon): output from the stream, keys by POST gathered for 30 ms, a resize when the panel changes size.
- Tests: Vitest for the hook's folding and each component; Playwright with MSW and `MockEventSource` (the pattern of the workflow page), screenshots regenerated in CI.

## Editions, durability and HA

- Community: the in-memory store. A restart ends every agent process (they are children of the server) and loses the transcripts.
- Plus: `R2dbcAgentConversationStore` in `plus/persistence-postgres`, tables `agent_conversation` and `agent_event` in the next free migration; terminal output stays in memory, the last screen is stored. At start, a conversation that is not `ENDED` is ended with `failure` and the reason "server restarted". A node with a `session` key and a harness with `resume` continues the agent's own session on the next run.
- One server owns a conversation's process. Commands are delivered in the same JVM. Routing a command to the owner in a cluster is not in this plan.

## Phases

One PR per phase unless it says otherwise. Each phase names its tests; the gate is `yk check` (`--full` before the PR).

| Phase | Scope | Size |
|---|---|---|
| AC0 | ADR `design/decisions/0004-agent-console.md`: settle D9 to D20 and Q1 to Q4 and check every code fact the plan marked open; record real sessions (Claude Code stream-json with a message during a turn and an interrupt; `gemini --acp` with a prompt, a permission request and a cancel) as fixtures; choose the Go libraries with the license check; artboards. Update this plan | M |
| AC1 | plugin-api `plugin.agent` (records, enums, gateway, handle, commands) and core `service.agent` (store, in-memory store, service, gateway, retention, settings). Nothing uses it yet | L |
| AC2 | Move `AGENT_SESSION` to `plugins/processors/agent-session`; the adapter seam; `JsonlAdapter` writes the conversation; payload `conversationId`; log lines as before | L |
| AC3 | REST read endpoints and the stream; OpenAPI snapshots | M |
| AC4 | Steering: the three control commands, `AgentSteering`, the POST endpoints, `Mailbox`, `steer` and `interrupt` in `JsonlAdapter`, `mode`, `idleTimeout`, `conversationTimeout`, `steerAudience` | L |
| AC5 | `AcpAdapter`, `AcpWire`, the `antigravity` preset; documented operator presets for the Claude Code, Codex and pi ACP adapters | L |
| AC6a | `yukta-cli pty-host` (Go) | M |
| AC6b | `PtyAdapter`, `launcher` in `ProcessExecutionSpec`, the terminal endpoints, the `terminal` preset, `yukta.agents.pty.*` | L |
| AC7a | UI: dock tabs, Agents tab, list and timeline (read only) | L |
| AC7b | UI: composer, requests in the timeline, interrupt, end, canvas state | M |
| AC7c | UI: terminal view | M |
| AC8 | CLI: `conversations` and `attach` (raw mode needs AC6b) | L |
| AC9 | MCP tools | M |
| AC10 | Supervisors: the feed event, the commands from supervisors, rule actions, the contract | M |
| AC11 | Preset `pi` recorded against the real CLI, and `codex` verified against the real CLI; no Java change expected, and one that is needed is a finding. No Antigravity `jsonl` preset: `agy acp` (AC5) covers it | M |
| AC12 | Plus: PostgreSQL store, migration, reconcile at start | M |
| AC13 | Docs (`docs/guides/ai/run-an-agent.mdx`, `docs/reference/plugins/ai.mdx`, API pages), example `20-agent-console`, a load test (10 conversations, 50 events per second, 20 stream clients), release notes with the breaking changes | M |

Order: `AC0` → `AC1` → `AC2` → `AC3` → `AC4`. Then in any order: `AC5`; `AC6a` → `AC6b`; `AC11`; `AC9`; `AC10`. UI: `AC3` → `AC7a`, `AC4` → `AC7b`, `AC6b` → `AC7c`; all three need the artboards of `AC0` approved. `AC8` after `AC4`. `AC12` after `AC1`. `AC13` last.

Breaking changes, all in AC2 and AC4: the Java package of the plugin; `ProcessExecutorAutoConfiguration` no longer provides it; a preset with `interactive` starts in interactive mode even with `approvals: NONE`.

## Test strategy

- **Fake agents, no vendor.** As `AgentSessionPluginTest` does today: presets whose command is a small script. One per protocol: JSON lines, ACP (answers `initialize`, `session/new`, `session/prompt`, sends updates and a permission request, honours `session/cancel`), and for `pty` a script that draws a prompt and echoes.
- **One scenario list for every adapter**: prompt to result; approve and reject a request; a message during a turn is queued and delivered; interrupt; end; `CONVERSATION` with two turns and an idle timeout; the process dies; the turn times out; cancelling the node kills the process tree.
- **Recorded sessions** of the real CLIs (AC0, AC11) as fixtures for the preset parsers, so a preset is pinned to what the CLI really prints.
- Core: store bounds and eviction, strictly increasing `seq`, history-then-live without a gap or a duplicate, a lagging subscriber, the `AgentSteering` table (role, reach, audience, agent key, capability, not live).
- Web: `WebTestClient` for every endpoint and error; the stream with `Last-Event-ID`.
- Go: `ptyhost` against `cat` and `sh`; `attach` against a test server (`cli/internal/testutil`).
- Boot: an acceptance test that runs a session with two fake agents and reads one merged stream; `expected-beans.txt`; the native-image hints for the new records (`@RegisterReflectionForBinding`).
- Architecture tests after AC1 and AC2.

## Risks

| Risk | Answer |
|---|---|
| A vendor changes its output or its ACP version | presets are data; recorded fixtures show the difference; the ACP version is checked at `initialize` |
| ACP names in this plan are from memory | **Checked in AC0** against `schema-v1.32.0`: none was wrong (ADR F7). The recorded `agy acp` sessions (ADR F8) pin them for the one agent that is shipped |
| Antigravity prints nothing without a TTY | AC11 records it; if it fails, its preset is `pty` |
| Claude Code does not queue a message written during a turn | then `steer` is false for it and `Mailbox` queues; the behaviour for the user is the same |
| The transcript grows without bound | caps per event and per conversation, retention, terminal ring |
| A slow client holds the stream | bounded buffer per subscriber, error and reconnect with the sequence number |
| Keystroke latency over POST | keys are gathered for 30 ms; if it is not good enough in practice, a WebSocket for the terminal only is a later, separate decision |
| The engine ends an idle chat node as stuck | heartbeat while `IDLE`; `getDefaultTimeout` returns `conversationTimeout` and `getMinimumTimeout` returns `idleTimeout` plus a margin; read in AC0 against `NodeActivity` and `NodeTimeouts`, to be proved by a virtual-time test in AC4 (ADR F2) |
| The module move breaks wiring or the native image | AC2 has its own PR; boot acceptance test and the native smoke run |
| Prompt injection through a transcript read by an AI client | sanitised results, an author on every message, `steerAudience` without agent keys by default |

## Not in this plan

- Agent nodes on remote workers. It needs the conversation and input gateways on the worker stream; see `design/plans/remote-workers.md`.
- A WebSocket transport, a full-screen TUI, a page that lists the live agents of all runs.
- Notifications when an agent waits without an input request (an idle chat, a terminal that wants attention): a link to the `NotificationGateway` of epic #2348 once both exist.
- "Always allow" for a tool from the timeline; requests as `CHOICE` or `TEXT` (D20).
- Recording and replaying a terminal session; sharing one terminal between two typing people beyond "last key wins".
- Sandboxing the agents (containers, read-only mounts): the hardening issue of the AI backend plan.
- Markdown rendering of messages.

## Questions, settled (ADR 0004, 2026-11-15)

- Q1: `steerAudience` defaults to operators only (`role:OPERATOR`). A supervisor steers only when the node names it.
- Q2: A `CONVERSATION` node that idles out ends on the port of its last turn (`success` if it succeeded, else `incomplete`) with `reason: idle`; no new port.
- Q3: One conversation per message the node works on; the Agents tab groups the rows under their node.
- Q4: The shipped presets are the native ones. The ACP adapters for Claude Code, Codex and pi are documented as example preset files, so the product needs no `npx`.

## Issues

Epic #2363. A sub-issue cites its section here, and this file wins over the issue text.

| Phase | Issue |
|---|---|
| AC0 | #2364 |
| AC1 | #2365 |
| AC2 | #2366 |
| AC3 | #2367 |
| AC4 | #2368 |
| AC5 | #2369 |
| AC6a | #2370 |
| AC6b | #2371 |
| AC7a | #2372 |
| AC7b | #2373 |
| AC7c | #2374 |
| AC8 | #2375 |
| AC9 | #2376 |
| AC10 | #2377 |
| AC11 | #2378 |
| AC12 | #2379 |
| AC13 | #2380 |
