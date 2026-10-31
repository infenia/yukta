# Multi-agent coordinator with a verifier and a hard stop

## The pain point

An agent that writes and then checks its own work tends to approve it. Chains of agents also fail
quietly: the same mistake is retried forever, or the run ends and nobody can tell how many tries it
took. This workflow keeps the researcher, the writer and the verifier as separate workflows, lets the
verifier see only the facts and the draft, sends its feedback back to the writer, and stops with a
clear failure after three attempts.

## The graph

```
coordinator
API_TRIGGER ─► VALIDATE ─ok─► research ─► seed ─► write-verify-loop ─► verdict (BRANCH)
                  │          (SUB_WORKFLOW)       (LOOP_PREDICATE,       ├─ accepted ─► accepted-report ─► ok
                  └invalid─► refuse ─► failed     max 3 x attempt)       └─ stopped ──► ask-counter ─► read-counter (KV get)
                                                                                          ─► hard-stop-report ─► failed

attempt  (one loop iteration)
API_TRIGGER ─► unpack ─┬─► write (SUB_WORKFLOW writer) ─► to-verifier ─► verify (SUB_WORKFLOW verifier) ─► result ─┬─► gate ─ pass ─► SUCCESS
                       │                                                                                          │        └ fail ─► RETRY
                       └─► count-key ─► count (KV incr)                                  artefact-key ─► save-draft (KV put) ◄┘

researcher: API_TRIGGER ─► research (stand-in) ─► SUCCESS
writer:     API_TRIGGER ─► pace-model-calls (DELAY) ─► write (stand-in) ─► SUCCESS
verifier:   API_TRIGGER ─► inspect ─► judge ─► SUCCESS     (inspect sees only facts and draft)
```

## What each node does

| Node | Type | What it does |
|---|---|---|
| `validate` | VALIDATE | Needs `runId` and `topic`; `fixAt` is optional |
| `research` | SUB_WORKFLOW | Runs `researcher`, a stand-in that returns three fixed facts |
| `seed` | MAPPER | Builds the first loop input: facts, empty feedback, attempt 0 |
| `write-verify-loop` | LOOP_PREDICATE | Runs `attempt` until its outcome is `SUCCESS`, at most 3 times |
| `unpack` | MAPPER | Reads the previous attempt's result (or the seed) and adds 1 to the attempt number |
| `count` | KV_STORE | `incr` on `<runId>:attempts` in namespace `multi-agent`; shared by every workflow that uses that namespace |
| `write` | SUB_WORKFLOW | Runs `writer`. The stand-in writes a sloppy draft until it has feedback and `attempt >= fixAt` |
| `to-verifier` | MAPPER | Hands the verifier the facts and the draft only (plus an opaque `task` it passes through) |
| `verify` | SUB_WORKFLOW | Runs `verifier`: lists facts missing from the draft and banned claims (`always`, `never`, `perfectly`, `guaranteed`) |
| `save-draft` | KV_STORE | `put` of the latest draft under `<runId>:draft` |
| `gate` | BRANCH | `pass` ends the attempt with outcome `SUCCESS`, `fail` with `RETRY` |
| `verdict` | BRANCH | Accepted draft, or the stop path |
| `read-counter`, `hard-stop-report` | KV_STORE, MAPPER | The stop message reads the attempt count back from the KV store |

`fixAt` is a test knob for the stand-in writer: the attempt number from which it uses the
feedback. `fixAt: 2` is accepted on attempt 2, `fixAt: 3` on attempt 3, `fixAt: 99` never.

The side branches of `attempt` (the two KV nodes) end in a console terminal, and every terminal's
message appears in the child's `results`. That is why the nodes that read a result pick the one with
`payload.results.?[#this['topic'] != null][0]` instead of `results[0]`.

## Make it yours

- **Agents:** replace the stand-ins. `research`, `write` and `inspect`/`judge` become `LLM_CALL`
  nodes (`provider`, `model`, `apiKey: "decrypted:..."`, `system`, `prompt` using `${payload.topic}`,
  `${payload.facts}`, `${payload.feedback}`), each followed by a `MAPPER` that puts the reply in
  `facts`, `draft` or `verdict`/`feedback` and passes the other fields along. Give the verifier a different
  model or prompt from the writer; keep `to-verifier` so it never sees the writer's notes. Tools
  can be added with `MCP_TOOL_CALL` inside any of the three workflows.
- **Cap:** change `maxIterations` in `write-verify-loop` and the text "cap 3" in `hard-stop-report`.
  Add `maxDuration` for a time cap too.
- **Rules:** the banned claims are in `inspect`; the required facts are `payload.facts`.
- **Shared state:** keep `namespace` the same in every KV node. A person or another workflow can
  read `<runId>:attempts` and `<runId>:draft` with their own `KV_STORE` `get`.
- **Failure:** `failed` is an `OUTCOME_TERMINAL` with outcome `FAILED`; a supervisor rule can key on it (see `docs/running/supervisors`).

## Pause and inspect a run

A person or an agent controls the same run through REST, the CLI or MCP. Each call is ordered and
recorded (`GET /api/control/audit`).

| Goal | REST | CLI | MCP |
|---|---|---|---|
| Pause the run and every child | `POST /api/executions/{id}/pause?tree=true` | `yukta-cli workflow pause <session> <id> --tree` | `control_workflow` action `PAUSE` |
| Resume | `POST /api/executions/{id}/resume?tree=true` | `yukta-cli workflow resume <session> <id> --tree` | `control_workflow` action `RESUME` |
| Hold the verifier when a draft contains a word | `POST /api/executions/{id}/nodes/judge/breakpoint?field=payload.draft&equals=...` (use the child's id) | `yukta-cli workflow node breakpoint <id> judge --field payload.draft --equals ...` | `set_node_breakpoint` |
| Step through a node | `.../nodes/{node}/step/enable`, `.../step` | `workflow node step enable\|next` | `control_node` `STEP_ENABLE`, `STEP` |
| Skip a node | `.../nodes/{node}/skip` | `workflow node skip` | `control_node` `SKIP` |
| Stop | `POST /api/executions/{id}/stop?mode=DRAIN` | `yukta-cli workflow stop` | `control_workflow` `STOP` |
| Read what happened | `GET /api/executions/{id}`, `.../logs` (every node prints its payload) | `yukta-cli workflow status` | `get_execution_result`, `get_execution_logs`, `get_execution_tree` |

Pausing only the parent leaves its children running; use `tree=true`. The `pace-model-calls`
delay (700 ms) is there so there is time to pause a live run, as `run.sh` does.

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-13-multi-agent","workflowId":"coordinator","payload":{"runId":"demo-1","topic":"Yukta","fixAt":2}}'
```

`request.json` holds that payload. Use a new `runId` for each run: it names the KV keys.

## Run

Needs no `PROCESS_EXECUTOR` allow-list: every stand-in is a MAPPER.

```bash
./examples/13-multi-agent/run.sh
```

It runs: accepted on attempt 2, accepted on attempt 3, hard stop after 3 attempts, a refused request,
and a run that is paused with `?tree=true` and resumed.

## Expected result

Last line `PASS 13-multi-agent: 5 paths`. In the coordinator logs an accepted run prints
`result=ACCEPTED, attempts=N, draft=...`; a stopped run prints
`result=HARD STOP, note=not verified after 3 attempts (cap 3), a person must take over`.
