# Content review with an editor gate

## The pain point

Drafts for a blog, a newsletter or a product page go back and forth between a writer and an editor
by email. Obvious problems (a banned claim, a piece that is too long) waste the editor's time, nobody
counts how many rounds a piece has had, and "approved" lives in someone's inbox. This workflow runs
the cheap checks first, asks the editor only about drafts that pass them, sends notes back to the
drafter, stops after three rounds, and publishes only what was approved.

## The graph

```
review (parent workflow)
API_TRIGGER ─► VALIDATE ─┬─ ok ──► start-round-1 ─► round-1 ─┬─ success ─────────────────────────┐
                         │                                   └─ retry ─► start-round-2 ─► round-2 ─┬─ success ──┤
                         └─ invalid ─► bad-brief ──┐                                          └─ retry ─► start-round-3 ─► round-3 ─┬─ success ─┤
                                                   │                                                                              └─ retry ─┐  │
              (failure of any round, i.e. editor reject) ──────────────────────────────────────────────────────────────────────────────────────┤  │
                                                   ▼                                                                                          ▼  ▼
                                      notify-stop ◄── stamp-notify-stop                                          stamp-published ─► publish ─► stamp-notify-ok ─► notify-ok ─► done
                                           └─► stopped (FAILED)

round (child workflow, one review round)
API_TRIGGER ─► draft ─► check ─► gate (BRANCH)
                                  ├─ clean ─► editor (WAIT_FOR_SIGNAL "review") ─► decide (BRANCH)
                                  │                                                 ├─ approved ─► approved-payload ─► SUCCESS
                                  │                                                 └─ changes  ─► changes-payload ──► RETRY
                                  └─ fix   ─► auto-notes ──────────────────────────────────────────────────────────► RETRY
```

Yukta workflows have no cycles, so the rounds are three `SUB_WORKFLOW` nodes that run the same
`round` workflow. Each round ends with an outcome terminal, and the parent routes on it
(`routeByOutcome`).

## What each node does

| Node | Type | What it does |
|---|---|---|
| `validate` | VALIDATE | Needs `title`, `slug` and `pitch`; `maxWords` is optional. A bad brief takes the `invalid` port |
| `start-round-N` | MAPPER | Builds the round input: brief, round number, notes from the last round |
| `round-N` | SUB_WORKFLOW | Runs `round`; leaves on `success` (approved), `retry` (changes wanted) or `failure` (rejected) |
| `draft` | MAPPER (stand-in) | Plays the drafter: round 1 uses the pitch as written, later rounds return a canned rewrite |
| `check` | MAPPER | Lists banned words found and compares the word count to `maxWords` |
| `gate` | BRANCH | `clean` goes to the editor; `fix` skips the editor and asks for a rewrite |
| `editor` | WAIT_FOR_SIGNAL | Waits for the signal `review`, up to 15 minutes |
| `decide` | BRANCH | `approve` publishes, any other decision is "changes"; the signal's `data` is the notes |
| `stamp-published`, `publish` | MAPPER, FILE_WRITE | Writes `<slug>.md` to the site folder (stand-in for a CMS call) |
| `stamp-notify-*`, `notify-*` | MAPPER, FILE_WRITE | Appends a line to `outbox.log` (stand-in for a chat message) |
| `done`, `stopped` | OUTCOME_TERMINAL | End of the happy path and of the "not published" path |

The editor has three answers: `approve`, `changes` with notes in `data`, and `reject`. A `reject`
signal fails the round (the node's `onReject` is `FAIL`), and the parent sends the "rejected"
notice. A `changes` decision is a normal decision name, not `reject`, because a rejected message is
dropped and its notes would be lost.

## Make it yours

- **Rules:** edit the banned words in the `check` node (the list `{'guaranteed', 'miracle', 'risk-free'}`
  appears twice in its `problems` expression) and the default length in `start-round-1`
  (`payload['maxWords'] != null ? ... : 60`). Add more rules by appending to `problems`.
- **Rounds:** to allow 5 rounds, copy a `start-round-N` / `round-N` pair and wire its `retry`
  and `failure` ports like the others.
- **Drafter:** replace the `draft` node with `LLM_CALL` (`provider`, `model`, `apiKey: "decrypted:..."`,
  `system` with your style guide, `prompt` built from `${payload.pitch}` and `${payload.notes}`).
  `LLM_CALL` returns text, so add a `MAPPER` after it that puts the text in `draft` and passes
  `title`, `slug`, `maxWords`, `attempt`, `notes` and `pitch` along. Rule checks and the editor
  stay as they are, so a model cannot publish on its own.
- **Publish and notify:** swap `publish` for `HTTP_REQUEST` (`method: POST`, `url` of your CMS,
  `body` from the payload) and `notify-*` for `NOTIFY_TERMINAL` (Slack webhook) fed by a MAPPER
  that sets `text`. The files go to `/tmp/yukta-examples/content-review`; change `baseDir`
  (it is not variable-resolved).
- **Approver:** the editor can answer from the web UI, the CLI or MCP (`signal_node`, `list_pending_approvals`).

## Trigger

`brief.json` is a ready payload.

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-12-content-review","workflowId":"review","payload":{"title":"Spring Sale","slug":"spring-sale","pitch":"Save on all widgets this spring."}}'

# what is waiting for the editor (the execution id is the round's, not the parent's)
curl localhost:9858/api/sessions/example-12-content-review/approvals

# answer: approve | changes (with notes) | reject
curl -X POST "localhost:9858/api/executions/<executionId>/nodes/editor/signal?name=review&decision=changes&data=shorter%20headline"
```

## Run

Needs no `PROCESS_EXECUTOR` allow-list: every stand-in is a MAPPER or FILE_WRITE.

```bash
./examples/12-content-review/run.sh
```

The script plays the editor. It applies the session and runs seven paths: approve at once, banned
words caught before the editor, changes then approve (notes carried), three rounds of changes
(stop), reject, too long (no editor needed), and an invalid brief.

## Expected result

The last line is `PASS 12-content-review: 7 paths`. Approved pieces appear as `site/<slug>.md` and
`PUBLISHED: ...` in `outbox.log` under `/tmp/yukta-examples/content-review`; stopped ones leave a
`NOT PUBLISHED: ...` line and no page.
