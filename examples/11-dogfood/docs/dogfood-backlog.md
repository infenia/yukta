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
