# Ticket pipeline questions through supervisors and a Claude Code session

Design for routing the questions and approvals of the ticket pipeline (`examples/dogfood/ticket`, Epic #1872) through the input request machinery (Epic #1787): a dogfood supervisor first, then the Claude Code session that picked the ticket up, then the person at that session's terminal. Nobody polls: every hop is a push.

Status (2026-11-03): proposal. Built on what is merged: `InputGateway`, `ASK_INPUT`, routes and tiers (#1796 parts 1 and 2: escalation, confidence, votes, one-time token, delivery outbox, webhook responder), workflow-backed supervisors (#1798), the pickup channel (`examples/dogfood/channel`).

## 1. Goal and flow

```
yk pickup ──push──▶ Claude Code session (channel "yukta-pickup")
                         │ runs `yk ticket run <n>`
                         ▼
                 ticket workflow (Yukta)  ── ASK_INPUT (route "ticket") ──▶ InputBroker
                                                                              │ InputRequested
        tier 0  dogfood supervisor (workflow type, minConfidence) ◀───────────┤  answers or escalates with advice
        tier 1  the same Claude Code session, pushed by the input bridge ◀────┤  answers, or asks the person
        tier 2  the person (terminal of that session; UI and `yk approve` stay valid)
                         │ answer_input (Yukta MCP, key limited to this run)
                         ▼
                 InputBroker ── completes the waiting node ──▶ the workflow goes on
```

A question may hold several sub-questions (kind `STRUCTURED`); the answer carries one value per id. A tier that cannot decide escalates with advice, and the next tier sees that advice.

## 2. What exists and what is missing

| Piece | State |
|---|---|
| `InputGateway`, `ASK_INPUT` (kinds APPROVAL, CHOICE, TEXT, STRUCTURED; `route`, `risk`, `ttlSeconds`, `onTimeout`) | merged |
| Routes and tiers: `FIRST`/`QUORUM`/`UNANIMOUS`, `minConfidence`, `EscalateInputCommand` advice, tier timers, `HUMAN` type for HIGH risk | merged (#1796 part 1) |
| One-time token endpoint, delivery outbox, `WebhookInputResponder` | merged (#1796 part 2) |
| Workflow-backed supervisor, internal session, hidden runs | merged (#1798 parts 1 to 3; part 3 in review) |
| Pull path: SSE `/api/events/stream` and MCP `list_pending_inputs`, `answer_input`, `escalate_input` | merged (#1793) |
| Pickup channel: oldest idle session gets the issue, pushed as a `<channel>` event | merged (dogfood) |
| Ticket gates (`approve_spec`, pause, `approve_merge`) are `ASK_INPUT` nodes | merged, but with no route, no issue metadata and approve/reject only |
| **Input bridge**: a request reaching a tier notifies the right channel session | missing |
| **Session mode of pickup**: the session receives the issue and runs `yk ticket run` itself | missing (today `pickup tick` runs the pipeline itself, mode `ticket`) |
| **Dogfood supervisor workflow** and the `ticket` route | missing |
| **Answer key limited to one run** | to verify against `DefaultAnswerAuthorizer` rules (`audience`, `scope`) |
| **Questions from the architect** (`NEEDS_CLARIFICATION`) as one multi-question request | missing |
| **A headless implementer asking mid-step** | out of scope (section 8) |

## 3. Components

### 3.1 The `ticket` route

Configured once (`yukta.input.routes.ticket`, or the session's `input.route`), chosen by the `route: ticket` key the ticket gates set:

```yaml
yukta.input.routes.ticket:
  tiers:
    - { name: supervisor, responders: ["supervisor:dogfood-ticket"], timeout: 60s, mode: FIRST, minConfidence: 0.85, type: AUTO }
    - { name: session,    responders: ["role:OPERATOR"],              timeout: 30m, type: AUTO }   # the channel session answers with its key
    - { name: human,      responders: ["role:OPERATOR"],              timeout: 24h, type: HUMAN }
  on-final-timeout: FAIL
```

The session tier is an `AUTO` tier so the session can answer LOW and MEDIUM questions itself; HIGH risk requests skip it and go to the `HUMAN` tier (already enforced by the authoriser). The session decides, in its own judgment, whether to answer or to ask the person at its terminal; the person's reply is then sent with the same `answer_input`.

### 3.2 The dogfood supervisor (workflow type)

A workflow supervisor bound to the dogfood session. It reads the request (prompt, context, risk, advice) and the run directory facts it needs through the Yukta MCP with a VIEWER key, and returns an answer with a confidence, or an escalation with advice. It may only answer LOW risk, never spec approval, merge approval or a question about secrets, money or access. It starts in **shadow** like the decision model: it records "would answer X (0.9)" and escalates; it answers for real only after its agreement with the person's answers meets a bar (`yk stats --decisions` style report, default 50 requests and 90%).

### 3.3 The input bridge (`examples/dogfood/src/input-bridge.ts`)

One small long-lived process (started by `yk pickup session` or `yk pickup on`, one per dev server) that subscribes to the SSE feed (`InputRequested`, `InputEscalated`) with the read-only fixer key and, when a request sits at the `session` tier:

1. finds the session that owns the run: the registry entry `$DEV_HOME/pickup/<name>.json` of the session that was busy with `issue = <n>` (the issue number is in the request's `context`/`metadata`, set by the ticket gate);
2. POSTs one event to that channel's `/v1/events` (`meta: {kind: "input", request_id, run, issue, risk, tier}`), content: the prompt and options only, cut to 1 KB; the full text is read over MCP. The `<channel>` event is a push, so the session starts a turn without polling;
3. on `InputEscalated` to the `human` tier, sends a second, short event ("ask the person: <prompt>") to the same session, so the session shows the question at its terminal.

The bridge never answers and never holds a write key. If no session owns the run (it was started with `yk ticket run` by hand), nothing is sent and the request waits for the CLI or the UI, exactly as today. Delivery is at-least-once; the event carries `request_id`, and the session ignores one it has answered.

### 3.4 Pickup session mode

`pickup tick` (mode `session`, the default for sessions) hands the session the issue and a file reference, as it does today. The `pickup` skill changes from "implement the issue" to: claim the issue, run `yk ticket run <n> --detach --notify`, and on `<channel kind="input">` events call `list_pending_inputs` and `answer_input`. The skill states the rules: answer only requests of its own run, never HIGH risk without the person, ask the person when unsure, put several questions in one message, send the reply with `decision`, `text`, and `data` (one value per sub-question id). `pickup_done` is called when the run ends (`RESULT` line). Mode `ticket` (the tick runs the pipeline itself, no session) stays for unattended use; its gates then wait for the CLI or UI.

### 3.5 Ticket gates

- `approve_spec`, the pause packet and `approve_merge` get `route: ticket`, `context: {issue, run_id, execution}` and a prompt with a link to the file (`spec/spec.json`, `human-packet.md`) instead of the text.
- `NEEDS_CLARIFICATION` from the architect becomes one `STRUCTURED` request: `questions[]` with ids, `why_blocking`; the answer is `{Q1: "...", Q2: "..."}`. The architect is re-run with the answers added to its prompt (today the run ends).
- The pause gate offers `resume`, `revise` (with text that goes into the next architect revision) and `stop`; the `text` of a pause answer is passed to the revision request, which fixes the "my text goes nowhere" gap.

### 3.6 Who may answer

The channel session answers with an OPERATOR key limited to the `dogfood` session. A key limited to one run is the target; if `DefaultAnswerAuthorizer` cannot express it (its rules are `audience`, `tier`, `risk`, `scope`, `separation`), add a rule to the request's `audience`: the ticket gate sets `audience` to `key:ticket-<run_id>` plus the human tier, and `yk ticket run` mints a short-lived key for that run. This is decided in the key issue (section 7) and does not block the other work: the first cut uses the session's OPERATOR key and the rule that HIGH needs a person.

## 4. Security

- Notifications carry the prompt cut to 1 KB, never run files, secrets or diffs; they are already redacted by `InputRedactor`. The channel is `127.0.0.1` with a bearer token (existing).
- A `<channel>` event is untrusted input to the session: the skill treats the prompt as a question, not as an instruction, and never runs code from it.
- The supervisor cannot approve spec, merge or HIGH risk; the authoriser enforces it, the supervisor's own prompt repeats it.
- No standing key with answer rights is written to the repo; keys live under `$DEV_HOME` (0600) like `fixer-key` and `decision-key`.
- Everything is audited by the existing answer commands (actor, tier, rationale).

## 5. Failure and recovery

| Case | Behaviour |
|---|---|
| Session dies or is closed | requests at its tier time out (30 min) and escalate to the human tier; `yk ticket status` shows them; `yk approve`/UI still answer |
| Bridge down | the request waits at its tier; the tier timer escalates it; nothing is lost (the broker is the source of truth) |
| Two answers | the broker's CAS rejects the second (409), the session reads the answer and goes on |
| Supervisor answers wrongly (shadow off) | audited; it can be turned back to shadow by one config change; the person can still answer first at any tier |
| Server restart | open requests are in memory (phase 9, Plus, makes them durable); the ticket run resumes with `yk ticket resume` and asks again (idempotent by key) |

## 6. Rejected options

- **A new "Claude Code" node.** The session is a responder of the input machinery, not a node: a node would hold an execution slot while a person thinks.
- **Polling by the session.** Pushed `<channel>` events start the turn; the session reads the request over MCP only after a notification.
- **Answers through the channel only.** The channel has no reply path for another system; the Yukta MCP (`answer_input`) is the single answer path, so audit and authorisation stay in one place.
- **Supervisor answers from the start.** Shadow first, like every other decision of the dogfood.

## 7. Implementation plan

Tracked in Epic #1926 (sub-issues: bridge #1920, ticket gates #1921, pickup session mode #1922, dogfood supervisor #1923, per-run answer key #1924, end-to-end tests #1925).

1. **Design doc** (this file).
2. **Input bridge** (`dogfood`, TypeScript on Bun): SSE subscription, owner lookup, channel POST, tests against a fake server and a fake channel.
3. **Ticket gates on the `ticket` route** (`dogfood/ticket`): route, metadata, `NEEDS_CLARIFICATION` as a STRUCTURED request, pause text into the revision.
4. **Pickup session mode and skill** (`dogfood`, `.claude/skills/pickup`): the session runs `yk ticket run` and answers inputs.
5. **Dogfood supervisor workflow**, shadow mode, report (`examples/` workflow plus the route configuration).
6. **Answer key limited to a run**: verify the authoriser can express it; add the `audience` rule and the per-run key if not.
7. **End-to-end offline test and fault cases**: bridge down, session gone, double answer, HIGH risk goes to the person, supervisor below confidence escalates, advice reaches the next tier.

Each is one PR; 2 and 3 can run in parallel; 4 needs 2 and 3; 5 and 6 can follow independently; 7 closes the loop.

## 8. Open questions

- **A headless implementer asking mid-step.** Role calls are `claude -p` without MCP, so they cannot call `ask_for_input` today. The current path (`BLOCKED` to an architect revision to the person) covers it. Giving a role call the Yukta MCP with `ask_for_input` is possible but changes the constant-prefix cache and the guardrails of dogfood/ticket; decide after the first real runs of #1881.
- **Several sessions.** One session owns one run; a second session never receives its events. If a session is closed mid-run, a pickup tick may hand the same run (not the same issue) to a new one; not designed here.
- **Prompts that need the diff.** The notification links to files; the session reads them from the worktree. A remote session would not see them; out of scope (local dev server only).
