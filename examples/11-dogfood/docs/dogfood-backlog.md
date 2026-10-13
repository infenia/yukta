# Dogfood backlog (dogfood tooling)

Defects and rough edges in the dogfood scripts and workflows themselves. Yukta defects found by the
loop go to the module's own `docs/dogfood-backlog.md` (e.g. `core/docs/dogfood-backlog.md`).

### DF-1: A watcher that attaches late misses the node events of a short run
- Status: open        - Severity: P3        - Kind: ux
- Found: 2026-10-13, workflow gradle-check, scope messaging, execution 6713c601-fa58-46ad-941a-7f761c71c0f4, stage spotless
- Evidence: `yukta-check.sh --detach -m messaging` followed a few seconds later by `yukta-watch.sh` printed only
  `WORKFLOW FAILURE` and the `RESULT` line; the `NODE spotless FAILURE` event had already gone by.
  `GET /api/events/stream` does not replay.
- Repro: break formatting in a file of the module, `--detach -m <module>`, start `yukta-watch.sh` after ~5 s.
- Impact: the RESULT line still names `failed_node`, so retry works; only the live detail is lost. `--watch`
  attaches within milliseconds. A fix would be to start the stream before the workflow (`--detach` cannot) or
  to replay recent events on the server.

### DF-2: yukta-worktree.sh failed to start a server from a fresh worktree (pnpm toolchain)
- Status: fixed in the working tree of this branch (commit sha pending)        - Severity: P2        - Kind: bug
- Found: 2026-10-13, tooling, execution -, stage server start
- Evidence: `Execution failed for task ':ui:pnpmInstall' ... A problem occurred starting process '.../ui/.gradle/pnpm/pnpm-v10.30.1/bin/pnpm'`
  in `.yukta-dev/<name>.log`. The script copied `ui/.gradle` only from the checkout it ran from (itself a fresh
  worktree) and nested `ui/.gradle/.gradle` when the destination already existed.
- Repro: run `yukta-worktree.sh <name>` from a worktree that has no `ui/.gradle`.

### DF-3: native server cannot stream control-bus events (missing record reflection hints)
- Status: open        - Severity: P1        - Kind: bug
- Found: 2026-10-13, workflow quick-check, scope all, execution c0cde99b-ac45-4548-8246-3e7e209ab991, stage -
- Evidence: `GET /api/events/stream` -> `UnsupportedFeatureError: Record components not available for record class
  com.infenia.yukta.plugin.control.ExecutionEvent$NodeStatusChanged` (`.yukta-dev/probe.log`); `yukta controlbus watch` prints `unexpected EOF`.
- Repro: `YUKTA_SERVER_MODE=native yukta-server.sh start x; yukta-check.sh --watch`
- Impact: `--watch`/`yukta-watch.sh` unusable on native; plain `yukta-check.sh` (log mode) works. Fix: register `ExecutionEvent` records (all accessors) for reflection.

### DF-4: yukta-check.sh hides a stale CLI (`session apply` silently prints help)
- Status: open        - Severity: P2        - Kind: ux
- Found: 2026-10-13, tooling, stage apply
- Evidence: with a July `cli/build/yukta-linux-amd64`, `session apply` printed the `session` help, exit 0 (output sent to /dev/null); the next step failed `Workflow not found for session`.
- Repro: run `yukta-check.sh` with a CLI built before `apply` existed.
- Impact: misleading error; fix: don't discard apply output, or check `yukta session apply --help`.

### DF-5: the long-running server on :8080 died during a `--watch` run
- Status: open        - Severity: P2        - Kind: error
- Found: 2026-10-13, workflow quick-check, execution f44043ba-1735-4b47-b093-826c7f3ced92
- Evidence: after `yukta controlbus watch` failed with `unexpected EOF`, `localhost:8080` refused connections and the process was gone; no log was captured (started from a terminal). Not reproduced on a probe server (survived DF-3).
- Repro: unknown.
