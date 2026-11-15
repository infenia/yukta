# ADR 0004: Agent console

- **Status:** accepted on 2026-11-15 for every decision and question below. Two parts of phase AC0 are **open**: the recorded `gemini --acp` session beyond `initialize` (see F8) and the maintainer's review of the artboards (see F11). They block the ACP preset (AC5) and the UI phases (AC7a to AC7c) respectively, nothing else.
- **Date:** 2026-11-15
- **Prepared by:** Claude Code, at the request of Arun, in phase AC0 of epic #2363 (issue #2364). The code facts were read from `origin/main` at c10778fe0; the protocol facts were recorded from real runs of `claude` 2.1.325 and `gemini` 0.46.0 and checked against the published Agent Client Protocol schema (`schema-v1.32.0`). **Decided by:** Arun, 2026-11-15, in two rounds of questions. Every proposal of the plan was accepted as written, with the additions in the "Outcome" column.
- **Plan:** `design/plans/agent-console.md`. Epic #2363. Builds on [ADR 0001](0001-input-requests.md) (input requests) and [ADR 0003](0003-notifications.md).

## Decisions

### Decided by Arun before the plan (D1 to D8)

| # | Decision | Outcome |
|---|---|---|
| D1 | How Yukta talks to "any" agent | Three tiers behind one conversation model: `acp` (Agent Client Protocol over stdio), `jsonl` (today's presets), `pty` (a pseudo-terminal) |
| D2 | What a client can do to a running agent | Steer and chat: message, interrupt, answer requests, end; a node may stay open (`mode: CONVERSATION`). The default stays: the node ends when the agent finishes |
| D3 | What hosts the pseudo-terminal | A hidden `yukta-cli pty-host` subcommand; no JNI in the native image |
| D4 | Transport to clients | SSE for output, POST for input; no WebSocket |
| D5 | Place in the web UI | An Agents tab in the dock of the workflow page, next to Logs |
| D6 | The CLI window | `yukta-cli attach <execution>`: line mode, and raw pass-through for a `pty` agent |
| D7 | Automated clients | The same operations as a person, by role, audited; a node limits who may steer with `steerAudience` |
| D8 | Relation to `AGENT_SESSION` | Evolve it, in its own module `plugins/processors/agent-session`; breaking changes accepted (alpha) |

### Proposed by the plan (D9 to D20), all accepted

| # | Decision | Outcome |
|---|---|---|
| D9 | What is stored | An `AgentConversation` record and an append-only list of `AgentEvent`s with one change sequence per store |
| D10 | How message, interrupt and end reach the agent | As control commands (`AgentMessageCommand`, `AgentInterruptCommand`, `AgentEndCommand`) on the path `AnswerInputCommand` takes; terminal keystrokes go directly, with the same authorisation |
| D11 | A message arrives while the agent works | Queued until the turn ends, unless the harness can take it mid-turn (`steer`); `interrupt: true` cancels the turn first. **Added by AC0:** the recorded Claude Code session takes a mid-turn message inside the same turn (F6), so the `claude-code` preset declares `steer: true`; ACP never steers |
| D12 | ACP permission options | Only approve and reject: approve picks the first `allow_once` option (else `allow_always`), reject the first `reject_once` (else `reject_always`). The option kinds and the `selected` outcome are in the schema (F7) |
| D13 | ACP client capabilities (`fs`, `terminal`) | None declared (`readTextFile: false`, `writeTextFile: false`, `terminal: false`). Checked: the real `gemini` accepts this at `initialize` |
| D14 | Timeouts of a conversation | `timeout` per turn, `idleTimeout` per wait for the next message (default 900 s), `conversationTimeout` for the whole node (default 14400 s). **Added by AC0:** how the engine fits this, in F2 |
| D15 | Is the terminal tier on by default | No: `yukta.agents.pty.enabled=false` |
| D16 | The run log | Unchanged: agent text and tool names keep going to the log as `agent` lines |
| D17 | Name of the resource | Conversation: REST `/api/conversations`, CLI `conversations` |
| D18 | New UI dependencies | Only `@xterm/xterm` and `@xterm/addon-fit`, loaded on demand; messages are plain text |
| D19 | Agent nodes on remote workers | Not in this epic. **Confirmed by AC0:** the worker stream carries no gateway calls (F3) |
| D20 | Agent questions that are not approvals | v1 maps every request to `APPROVAL`; presets may map to `CHOICE` or `TEXT` later. ACP has `elicitation/create` for this (F7), recorded as the hook for that later phase |

### Open questions of the plan (Q1 to Q4), settled

| # | Question | Outcome |
|---|---|---|
| Q1 | Who may steer by default | Operators only: the default `steerAudience` is `role:OPERATOR`. A supervisor steers only when the node lists it (`supervisor:<id>` or `supervisor:*`) |
| Q2 | Port when a `CONVERSATION` node idles out | The port of the last turn (`success` if it succeeded, else `incomplete`); the payload says `reason: idle`. No new port |
| Q3 | Grain of a conversation | One conversation per message the node works on; the Agents tab groups the rows under their node |
| Q4 | ACP adapters for Claude Code and Codex in the shipped presets | Document only, as example preset files; the product ships no preset that needs `npx` |

## Findings of the code check (what the plan said "to confirm")

**F1. `DefaultAnswerAuthorizer` matches by exact selector, and an `agent` key is not special.** `matches(actor, selector, tier)` is private and static. For `HUMAN` and `AGENT` actors alike it accepts `key:<name>`, `role:OPERATOR` (an OPERATOR or ADMIN) and `role:ADMIN` (an ADMIN only); for a supervisor `supervisor:*` or `supervisor:<id>`. There is no `role:VIEWER`, and no rule "at or below the caller's role", and a key marked `agent` matches `role:OPERATOR` like any operator key. The plan's `AgentSteering` rule ("a key marked `agent` matches only a selector that names it") therefore needs its own check. Decision for AC4: extract the matching into a small shared class (`AudienceMatcher`) used by both authorisers, add the `agent` restriction in `AgentSteering`, and keep the selector grammar of the plan (`role:OPERATOR`, `role:ADMIN`, `key:`, `supervisor:`), dropping the plan's wording "at or below the caller's role", which the existing grammar does not have.

**F2. An idle chat node survives if the plugin heartbeats, and the engine has two floors we can use.** A node's idle timeout is `timeoutSeconds`, else `Plugin.getDefaultTimeout(config)` (`NodeTimeouts`); `NodeActivity.touch()` restarts the clock on every input, output element, heartbeat (`WorkflowContext.HEARTBEAT_KEY` in the Reactor context) and live log line; `engine.deadlineMs` is never restarted. `Plugin.getMinimumTimeout(config)` raises a configured `timeoutSeconds` to a floor (`NodeTimeouts.atLeastMinimum`). Consequences for AC4: `getDefaultTimeout` returns `conversationTimeout` for a `CONVERSATION` node (today it returns `AgentSessionConfig.stepTimeout`); `getMinimumTimeout` returns `idleTimeout` plus a margin, so a user-set `timeoutSeconds` cannot kill a chat that is merely waiting; the adapter calls the heartbeat while `IDLE` and `WAITING`. This was read, not run; AC4 adds a test with virtual time.

**F3. The worker stream has no gateway calls.** `NodeFrame.Kind` is `START`, `MESSAGE`, `HEARTBEAT`, `COMPLETE`, `ERROR`. A node on a remote worker cannot reach the conversation or input gateways. D19 stands.

**F4. The `rules` supervisor declares actions in two places, and a new command needs both.** `EventRule.commandFor` maps `then.command` (lower case) to a command through a `switch`; today it knows eight commands (`pauseworkflow` to `restartfromnode`), while an answer or escalation uses `then.answer` and `then.escalate`. The record `RuleSet.Then` has the fields `command`, `mode`, `reason`, `immediate`, `from`, `answer`, `escalate`; a message needs a new field (`text`, and `interrupt`). The allowed command names are `SupervisorCapabilities.KNOWN_COMMANDS`, listed by hand (a native image cannot read the permitted subclasses; `SupervisorCapabilitiesTest` checks the list on the JVM), and `SupervisorContract` plus the page `docs/reference/supervisors/contract.mdx` list the same names, pinned by a test. AC10 changes all four places.

**F5. The allow-list check is private, and the `launcher` fits where the plan puts it.** `ProcessExecutorGateway.refusalFor(spec)` checks `spec.command().get(0)` against `yukta.process.allowed-commands` (empty list: off unless `allow-any`), refuses `useShell` when a list is set, and checks the environment and the working directory; it is private and runs before `actualCommand` is built. So AC6b adds `launcher` in `ProcessExecutionSpec` and prepends it when `actualCommand` is built, after the check: an operator allows `agy`, not `yukta-cli`. No new public method is needed. **Side finding:** the clean environment of #2327 passes no `HOME`; the dev server's `yk tool go ...` fails on it ("GOCACHE is not defined and neither `$XDG_CACHE_HOME` nor `$HOME` are defined"), and agent CLIs read `HOME` for their login. The operator must list `HOME` (and `XDG_*`) in `yukta.process.pass-env` for `AGENT_SESSION`; the docs of AC13 say so. `yk tool` can pass it per call (`yk tool -C <dir> env HOME=$HOME go ...`), which is how the spike below ran.

## Findings of the recorded sessions

Fixtures: `plugins/processors/process-executor/src/test/resources/agent-sessions/` (they move with the plugin in AC2). How they were recorded and cleaned: `design/decisions/0004-agent-console/recording/README.md`.

**F6. Claude Code (`claude` 2.1.325, `-p --input-format stream-json --output-format stream-json --verbose --permission-prompt-tool stdio`).**

- *Approval:* the agent prints `{"type":"control_request","request_id":…,"request":{"subtype":"can_use_tool","tool_name":"Write","input":{…},"tool_use_id":…,"permission_suggestions":[…]}}`; the answer is `{"type":"control_response","response":{"subtype":"success","request_id":…,"response":{"behavior":"allow","updatedInput":<the input>}}}` (a `deny` reply was not recorded; AC2 keeps the shape the existing `claude-code` preset writes). Tools that need no approval (`Bash` here) produce no request.
- *Message during a turn, no interrupt:* **taken inside the same turn.** One `result` came back and its text contained the word asked for in the message written during the turn. The plan assumed "after the turn". So `steer: true` for this preset (D11), and the message needs no queue.
- *Interrupt:* write `{"type":"control_request","request_id":"<id>","request":{"subtype":"interrupt"}}`. The agent answers `{"type":"control_response","response":{"subtype":"success","request_id":"<id>","response":{"still_queued":[]}}}`, then ends the turn with a `result` of `subtype: "error_during_execution"`, `is_error: true`, `terminal_reason: "aborted_tools"`, preceded by a `user` line "[Request interrupted by user for tool use]". `still_queued` lists user messages not yet taken; empty here.
- *The process survives an interrupt:* a message written afterwards starts a normal turn (`success`). The adapter therefore treats `error_during_execution` after our own interrupt as a cancelled turn, not as a failure of the node (the plan's step "cancelled sends the next queued message, or goes `IDLE`").
- *Exit code:* closing stdin after an interrupted last turn ended the process with 1 in one run, and with 0 after a successful last turn in the other; the adapter must not read a conversation outcome from the exit code alone.
- A real session prints more than the protocol needs: `system` lines for hooks, UI logs and token counts, a `rate_limit_event`, and a long `init` (tool and MCP server lists). The `JsonlAdapter` ignores them; the fixtures drop them (see the recording README).

**F7. Agent Client Protocol, checked against `schema-v1.32.0` (`schema/v1/schema.json` and `meta.json`).** Every method and field the plan lists exists with the name the plan uses: `initialize` (`protocolVersion`, `clientCapabilities.fs.{readTextFile,writeTextFile}`, `clientCapabilities.terminal`), `authenticate` (`methodId`), `session/new` (`cwd`, `mcpServers`, both required), `session/load` (`sessionId`, `cwd`, `mcpServers`), `session/prompt` (`sessionId`, `prompt`: an array of content blocks; `type: "text"` has `text`), its answer `stopReason` (`end_turn`, `max_tokens`, `max_turn_requests`, `refusal`, `cancelled`), the notification `session/cancel` (`sessionId`), the client request `session/request_permission` (`sessionId`, `toolCall`, `options[].{optionId,name,kind}`, kinds `allow_once`, `allow_always`, `reject_once`, `reject_always`), whose answer is `outcome: {"outcome":"selected","optionId":…}` or `{"outcome":"cancelled"}`, and the notification `session/update` with `sessionUpdate` one of `agent_message_chunk`, `agent_thought_chunk`, `tool_call` (`toolCallId`, `title`, `kind`, `status`), `tool_call_update`, `plan` (`entries[].{content,priority,status}`). What the plan did not name and AC5 must handle: `session/update` has more kinds (`user_message_chunk`, `available_commands_update`, `current_mode_update`, `config_option_update`, `session_info_update`, `usage_update`, `notice`, `compaction_update`, `compaction_summary_chunk`), all of which the adapter ignores; the agent may send `$/cancel_request`; the client method `elicitation/create` exists (the hook for D20); `AgentCapabilities.loadSession` decides `session/load` (the real `gemini` sets it `true`). A second schema directory `schema/v2` exists upstream; v1 is the one the real `gemini` speaks (`protocolVersion: 1` in its answer) and the plan pins it. No method or field name of the plan was wrong.

**F8. Gemini (`gemini` 0.46.0, `gemini --acp`): only `initialize` could be recorded.** `initialize` answers `protocolVersion: 1`, `agentInfo {name: gemini-cli, version: 0.46.0}`, `agentCapabilities {loadSession: true, promptCapabilities {image, audio, embeddedContext}, mcpCapabilities {http, sse}}` and four `authMethods` (`oauth-personal`, `gemini-api-key`, `vertex-ai`, `gateway`). `session/new` and `authenticate` then fail with JSON-RPC error `-32000` "This client is no longer supported for Gemini Code Assist for individuals … migrate to the Antigravity suite of products", because this machine is logged in with the personal OAuth tier and has no API key. So the recorded `session/new`, `session/prompt`, permission request and `session/cancel` are **missing**. The fixture `gemini-acp/initialize-and-auth-failure.jsonl` is what really happened, nothing was invented. The script `recording/record_acp.py` records the whole flow in one run as soon as `gemini` can log in (with `GEMINI_API_KEY` or Vertex settings in the environment); closing this is a condition for AC5, not for AC1 to AC4. A consequence for the `gemini` preset: its `authMethod` setting must be able to name `gemini-api-key` or `vertex-ai`, and an `-32000` from `session/new` becomes a node failure with the agent's message, not a retry.

## Libraries for `pty-host` (AC6a)

Spike: `design/decisions/0004-agent-console/spike/` (a separate Go module, not part of `cli/`): `sh` runs in a PTY from `github.com/aymanbagabas/go-pty` v0.2.3 and a virtual screen prints what the program drew, once with `github.com/hinshun/vt10x` and once with `github.com/charmbracelet/x/vt` v0.1.0. Both print the screen correctly (ANSI colour removed, two lines). License check: `trivy fs --scanners license` over the spike's `go.mod`, the scanner of the CI job "Trivy License Scanning" (`license-compliance.yml`, severity MEDIUM and above), resolved 22 modules, all MIT (18) or BSD-3-Clause (4), none at MEDIUM or above. The other CI job of that workflow, Licensee, validates Gradle dependencies only. The `licenses` stage of `yk check` was not run on the spike, because it is not part of `cli/` yet; AC6a adds the modules to `cli/go.mod` and runs the gate then.

**Decision:** `go-pty` for the PTY and ConPTY, and `charmbracelet/x/vt` for the screen: it is maintained (last push 2026-11-06, against 2023 for `vt10x`, whose newest version is a 2022 pseudo-version) and aimed at the full-screen programs the agents draw. It is pre-1.0, so `cli/internal/ptyhost` hides it behind a small `screen` interface (`Write`, `Text`, `Cursor`; the two libraries offer `String()` and `CursorPosition()` or `Cursor()`), and swapping is a local change. Not measured: wide characters, 256 colours, and the cost of the extra modules in the binary size of `yukta-cli`; AC6a measures the size and the wide-character case before it merges, and falls back to `vt10x` if the size grows more than the maintainer accepts.

## Artboards (F11)

Eight artboards in one Design canvas: https://claude.ai/artifact/ARyM7fCFLLo6gjBsjG2PmK (private; only its owner can open it until shared). 1 dock with tabs (Logs active, an agent waits), 2 Agents tab with one agent, 3 several agents with the merged "All" view, 4 an open request in the timeline, 5 a queued message, 6 a terminal agent, 7 the empty state, 8 narrow width. The design system artifact of the earlier redesign ("Yukta Design System") could not be opened from this account (not found or not shared), so the artboards use the tokens of `ui/src/index.css` directly (Inter and JetBrains Mono, hue 262, radius 0.625 rem) and the status colours of the UI. They were not rendered or screenshotted by the author. **Not reviewed yet:** AC7a to AC7c wait for Arun's approval, as the epic says.

## Consequences

- The plan is updated in this PR (F1 to F8 and the answers to Q1 to Q4 in its text, no "to confirm" left).
- AC1 to AC4 can start. AC5 waits for the Gemini recording (F8). AC6a starts with the size and wide-character check above. The UI phases wait for the artboard review.
- Fixtures and recording scripts are test-only and carry no secrets: the session id is a fixed value, paths are `/work`, and nothing of the user's account is in them (see the recording README).
