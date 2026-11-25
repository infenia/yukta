# ADR 0013: Structured agent questions (choice, text) from terminal agents

- **Status:** proposed. D1, D2, D6 and D10 were decided by Arun on 2026-11-25; the rest is proposed and needs review before the follow-up issues start. Built in this PR: the recordings, and the stop-gap of D10. Not built: everything else (the prototype lives on the branch `spike/2706-prototype`, not merged).
- **Date:** 2026-11-25
- **Prepared by:** Claude Code, at the request of Arun, for the spike #2706. The code facts come from `origin/main` at fe642fbb3. The protocol facts come from real runs of `claude` 2.1.332 and `agy` 1.5.10 (Antigravity), checked against the published Agent Client Protocol schema `schema-v1.32.0` (and `schema-v1.35.0`, which is the same for elicitation). **Decided by:** Arun, 2026-11-25, one round of questions (D1, D2, D6, D10).
- **Epic:** #2706, under the agent console epic #2363. This replaces decision D20 of [ADR 0004](0004-agent-console.md) ("v1 maps every request to `APPROVAL`") and builds on [ADR 0001](0001-input-requests.md).
- **Number:** the issue asked for 0005, but [0005](0005-editions.md) is taken; 0013 is the next free number.

## Context

When an agent asks the user a question, Yukta turns it into an `APPROVAL` (ADR 0004, D20). The recordings below show what that costs for each agent:

- **Claude Code over `jsonl`.** `AskUserQuestion` is an ordinary `can_use_tool` request, so the preset's `requestWhen` takes it as an approval. "Approve" sends the input back unchanged, and the model is then told **"The user did not answer the questions."** (fixture `ask-user-question-plain.jsonl`). That is a live bug, fixed for now by D10.
- **ACP (Antigravity).** Yukta declares no elicitation capability (ADR 0004, D13). Antigravity then writes the question as plain text (`- [1] Postgres / - [2] Redis`) and ends its turn (`elicitation-nocap.jsonl`). The user can only answer with a chat message, and only when the node is a `CONVERSATION`.
- **`pty`.** `attentionPattern` sets `WAITING`, and the user answers with raw keys.

The input contract can hold part of a question today. `InputKind.CHOICE` takes options as `InputOption(id, label)` and needs two or more of them. `TEXT` takes free text. An `InputResponse` carries `decision` (one id, at most 128 characters), `text` and `data`. `AnswerShape` (core) checks that a `CHOICE` decision is one of the option ids. The contract has no option description, no multi-select, and no way to say "choose an option or type your own", which every agent below offers.

## Findings of the recorded sessions

Fixtures: `plugins/processors/agent-session/src/test/resources/agent-sessions/claude-code/ask-user-question-*.jsonl` (6) and `.../antigravity-acp/elicitation-*.jsonl` (5). How they were recorded and cleaned: [`0013-structured-agent-questions/recording/README.md`](0013-structured-agent-questions/recording/README.md).

**F1. Claude Code headless (`claude -p --input-format stream-json --output-format stream-json --verbose --permission-prompt-tool stdio`, 2.1.332).** `AskUserQuestion` is in the tool list of `init` and needs permission, so it comes as:

```json
{"type":"control_request","request_id":"…","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion",
 "input":{"questions":[{"question":"Which checks should run?","header":"Checks","multiSelect":true,
   "options":[{"label":"Lint","description":"Run the linter"},{"label":"Test","description":"Run the test suite"},
              {"label":"Build","description":"Run the build"}]}]}}}
```

The answer is `allow` with the same input plus `answers`, a map from question text to answer text:
`{"behavior":"allow","updatedInput":{…the input…,"answers":{"Which checks should run?":"Lint, Test"}}}`. In every case the model repeated exactly the answer it was given:

| Fixture | Answer sent | Tool result the model got |
|---|---|---|
| `single` | `"Redis"` | `Your questions have been answered: "Which database?"="Redis". …` |
| `multi` | `"Lint, Test"` (labels joined with `", "`, the TUI's own form, see F2) | `…="Lint, Test"` |
| `other` | `"SQLite, embedded"` (not an option) | `The user answered: … Read the answers carefully …` |
| `several` | two questions in one call, one answer each | `…"Which database?"="Redis", "Which region?"="US"` |
| `plain` | `allow`, input unchanged (today's approval path) | **`The user did not answer the questions.`** |
| `deny` | `deny`, `message: "The user answered: Redis"` | the message, as an error (`is_error: true`); the model used it |

So the protocol reply is the right channel. `deny` with a message also reaches the model, but as a tool error, which is the right shape for "no answer" (D6). There is no id per option: the answer is the label, or any text.

**F2. Claude Code TUI (`claude-tui` preset, captured with `tmux`).** A single-choice question:

```
 ☐ Database
Which database?
❯ 1. Postgres
     relational
  2. Redis
     key-value
  3. Type something.
──────────────────────────────
  4. Chat about this
Enter to select · ↑/↓ to navigate · Esc to cancel
```

A multi-select question shows `[ ]` boxes, a tab bar `←  ☐ Checks  ✔ Submit  →` and a `Submit` line. With several questions there is one tab per header. Keys, each checked on the screen:

- **Single choice:** the option's digit. It selects and submits at once.
- **Multi-select:** the digit of each option toggles it (`[✔]`). Then `→` opens "Review your answers", and `Enter` submits.
- **Free text:** the digit of "Type something." Then type the text and press `Enter`.

Afterwards the screen shows `● Answered Claude's questions: ⎿ · Which checks should run? → Lint, Test`. The preset's `attentionPattern` (`Esc to cancel|Enter to confirm`) matches the question screen. It also matches the trust dialog and tool approvals, so `WAITING` cannot say which one is open. A parser has to recognise a question by its header line (`☐`/`☒`), the numbered options and "Type something".

**F3. ACP: elicitation exists in v1, and Antigravity uses it.** `elicitation/create` and `elicitation/complete` are client methods in `meta.json` of `schema-v1.32.0`, not only in v2. An agent may send a form elicitation only after the client declares `clientCapabilities.elicitation.form = {}` at `initialize`. The form is a flat JSON Schema:

- a single choice is `type: string` with `oneOf: [{const, title, description?}]`;
- a multi-select is `type: array` with `items.anyOf: [{const, title}]`;
- free text is `type: string`.

The answer is `{"action":"accept","content":{…}}`, `{"action":"decline"}` or `{"action":"cancel"}`. With the capability declared, `agy acp` 1.5.10 turned its `ask_question` tool into:

```json
{"method":"elicitation/create","params":{"sessionId":"…","toolCallId":"call_…","mode":"form","message":"The agent has a question.",
 "requestedSchema":{"type":"object","properties":{
   "q1":{"type":"string","title":"Which database should I use?","oneOf":[{"const":"1","title":"Postgres"},{"const":"2","title":"Redis"}]},
   "q1_other":{"type":"string","title":"Other","description":"Optional free-form answer to: Which database should I use?"}}}}}
```

Recorded and accepted:

| Answer | What the model said |
|---|---|
| `{"q1":"2"}` | `GOT: Redis` |
| `{"q1":["1","2"]}` (multi) | `GOT: Lint, Test` |
| `{"q1_other":"SQLite, embedded"}` | `GOT: SQLite, embedded` |
| `decline` | `GOT: User Skipped` |

Points to note:

- The request id was again a string (`"1"`, as in ADR 0004, F8).
- `ask_question` also shows as a `tool_call` and `tool_call_update`, which the conversation already displays.
- Option ids are agent-made (`"1"`, `"2"`).
- Free text is a sibling property named `<key>_other`. That is Antigravity's convention, not part of the protocol.
- Two answers in one run (`q1` and `q1_other`, from a first try of the recorder) were both passed to the model as `"Postgres, Redis"`.

**F4. Codex and others: deferred, nothing recorded.** `codex` is not installed on the recording machine, and the only Codex fixture is an expired key. The `codex` preset runs `codex exec --json`, which has no input channel. Whether Codex can send a structured question, and through which protocol (its app-server protocol, or ACP through an adapter), is left to its own issue. `pi` has no question tool in its preset either.

**F5. Prototype (branch `spike/2706-prototype`, commit 640e7c2cb, not merged).** `AgentQuestions` parses both forms into the question model of D3. It builds the `InputRequestSpec` (today's contract: `CHOICE` or `TEXT`, with multi-select and free text carried in `context`) and renders both replies. `AgentQuestionsTest` replays the fixtures: for `claude single|multi|other|several` and `acp single|multi|other`, the reply rendered from an answer equals, as JSON, the reply the real agent accepted in the recording (8 tests, green through `yk gradle`). With the live recordings, this covers capture, show (as the input request), answer and acceptance for two agents. What the prototype does not do: wire into `AgentRequests` or `AcpSession`, or change the UI. It also uses `selected` and `text` as decisions, where D4 reserves `_selected` and `_text`.

## Decisions

| # | Decision | Outcome |
|---|---|---|
| D1 | How a question fits `plugin.input` | **Extend `CHOICE`** (decided by Arun). `InputOption` gets an optional `description`. `InputRequestSpec` gets `multiSelect` and `allowText`, both only for `CHOICE` (refused for other kinds). A question with no options is `TEXT`. One input request per question. Rejected: `STRUCTURED` with a schema (the UI and CLI would have to render generic forms, and the answer is free data), and a new `QUESTION` kind holding a list of questions (the biggest contract and UI change). |
| D2 | Go or no-go per protocol | **`jsonl`: go** (Claude Code, F1). **`acp`: go** (form elicitation, F3; declare `elicitation.form`, never `url`). **`pty`: deferred.** The question screen can be parsed (F2), but the parser is a new piece per TUI and breaks when the TUI changes. Today's `WAITING` plus keys stays, and a later issue adds a `claude-tui` parser. **Codex: deferred** (F4). (Decided by Arun.) |
| D3 | The canonical question | `AgentQuestion(key, header, text, options[{id, label, description}], multiSelect, allowText)`. `key` is how the agent names the question in its answer: the question text for Claude, the property name for ACP. Ids are the agent's own (`const`) or `1..n` in order (Claude has none). It maps to an `InputRequestSpec`: `prompt` = `header: text`, `context` = `{harness, agentKey, group, index, of}`. `group` ties together the questions of one agent call, and `index`/`of` place this question among them. |
| D4 | The answer (`answer_input`, REST, UI) | One option: `decision` = its id. Multi-select: `decision` = `_selected`, `data.selected` = the ids (one or more, each an option id). Free text (only with `allowText`): `decision` = `_text`, `text` = the answer. `_selected` and `_text` are reserved: an option id may not start with `_`. `AnswerShape` checks the three forms. `decision` stays at most 128 characters; the lists go in `data`, so 20 options fit. MCP `answer_input` already takes `decision`, `text` and `data`; only its description changes. |
| D5 | The adapter SPI | In `agent-session`, not `plugin-api`: `QuestionResponder` with `List<AgentQuestion> capture(frame)` and `String reply(asked, List<InputResponse>)`, plus `String noAnswer(asked, reason)`. One per protocol: `ClaudeQuestions` (`jsonl`, chosen by a preset `questionWhen` match such as `{"request.tool_name":"AskUserQuestion"}`, so the core names no tool) and `AcpElicitation` (`acp`). A later `pty` responder renders keys from a preset `questionKeys` block (digit, toggle, submit, text), so no TUI keys are hard-coded. `AgentRequests` asks every question of a call at once and replies once, when all are answered. The UI, `yukta-cli attach` and MCP all read the same `InputRequest` and the same `REQUEST` event, so every client sees the same question. A shared screen model is only needed for `pty` (deferred). |
| D6 | An unanswered question | **Tell the agent there was no answer** (decided by Arun). Claude: `deny` with `message: "The user did not answer: <reason>"`, which the model sees as a tool error and goes on (F1). ACP: `{"action":"cancel"}`; a user who declines in the UI sends `decline`. If one question of a call expires or is cancelled, the whole call gets the no-answer reply, and the other questions of the group are cancelled. The node's `onTimeout` stays `FAIL` by default. `USE_DEFAULT` needs a `defaultDecision` that is an option id. |
| D7 | Where the question is shown | The input request card of the UI and the `REQUEST` event of the Agents tab get the header, the options with their descriptions, checkboxes for multi-select and a text field when `allowText`. `yukta-cli attach` prints numbered options and reads `1`, `1,3` or text. One form for all three clients, built from the spec, not from agent output. |
| D8 | Risk and audience | Like the approvals: the node's `approvals` settings (`risk`, `ttl`, `audience`). A question needs `approvals: ASK`. With `approvals: NONE` it gets the no-answer reply of D6 at once, as a request is denied today. |
| D9 | Several questions in one call | One request per question (D1), all opened together and linked by `context.group`. Claude allows one to four per call. ACP sends them as several properties of one schema, minus the `_other` siblings, which become `allowText`. |
| D10 | The live bug of the approval path | **Fixed in this PR** (decided by Arun). The `claude-code` preset's interactive command adds `--disallowedTools AskUserQuestion`. Without the tool, the model asks in plain text, which the user answers in the conversation; this was checked with the same CLI. The flag is removed when the `jsonl` follow-up lands. Pinned by `AgentPresetTest.claudeCodeConversation_disallowsAskUserQuestion`. |

## Follow-up issues (to file once this ADR is accepted)

| # | Issue | Size |
|---|---|---|
| 1 | `plugin-api` and `core`: `InputOption.description`, `InputRequestSpec.multiSelect`/`allowText`, `AnswerShape` for `_selected`/`_text`, refresh the OpenAPI snapshots, docs of `answer_input` | M |
| 2 | `agent-session`: `AgentQuestion`, `QuestionResponder` and `ClaudeQuestions` (preset `questionWhen`), grouped asks and one reply, D6 no-answer; remove the D10 flag; tests from the `claude-code` fixtures | M |
| 3 | `agent-session`: `AcpElicitation`, declare `elicitation.form`, `accept`/`decline`/`cancel`; tests from the `elicitation-*` fixtures | S |
| 4 | `ui`: the question form in the input card and the Agents tab (descriptions, multi-select, "Other" text); Playwright spec | M |
| 5 | `cli`: `yukta-cli attach` and `inputs answer` for choices, multi-select and text | S |
| 6 | `pty` (deferred): a `claude-tui` question parser over the settled screen, and the preset `questionKeys` responder (F2) | L |
| 7 | Codex (deferred): record its question channel, then go or no-go | S |

## Consequences

- A question from Claude Code or an ACP agent reaches the user with its options, and the answer reaches the model the way the model expects. This was shown with both agents.
- `plugin-api` grows by three optional fields. Existing `CHOICE` and `APPROVAL` requests are unchanged. A new option id may not start with `_`.
- Until follow-up 2 lands, Claude Code conversations cannot use `AskUserQuestion` (D10). The model asks in its text instead. That works in a `CONVERSATION` node; a `TASK` node ends with the question as its result.
- Still open: the `pty` tier and Codex (deferred), ACP `url` elicitation (not declared), and how a `rules` supervisor answers a choice (`then.answer` takes a decision today; `_selected` would need `data`).
