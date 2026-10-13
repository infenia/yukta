---
name: yukta
description: Use to verify changes in the Yukta repo by running its staged `gradle check` workflow on a local Yukta server via the yukta CLI, to watch a run and steer it (retry a stage, pause) and record defects it finds, or to start/stop a local Yukta server. Use instead of choosing gradle quality tasks yourself.
allowed-tools: Bash, Read
---

# Using Yukta to develop Yukta

The full `gradle check` is encoded as a Yukta workflow (`examples/11-dogfood/session.json`).
Do not decide which checks to run: run the workflow and act on its result.

## Run the checks
```bash
examples/11-dogfood/scripts/yukta-check.sh        # checks the current checkout; exit 0 = SUCCESS
```
1. Read the final line: `RESULT status=<S> failed_node=<node> execution=<id> session=<id>`.
2. If `SUCCESS`: done. Otherwise fix only what `failed_node` reports (stages are fail-fast, in order:
   spotless, compile, tests, coverage, style, spotbugs, licenses, security, cli).
3. Read that stage's report (see `examples/11-dogfood/README.md` table), fix, rerun the script.
4. Formatting failures: run `./gradlew spotlessApply`, then rerun.

Scope it when the whole project is overkill:
```bash
examples/11-dogfood/scripts/yukta-check.sh -m core                          # staged gate for one module
examples/11-dogfood/scripts/yukta-check.sh :core:test --tests '<FQCN>'      # any gradle args, one stage (failed_node=gradle)
```

For quick inner-loop feedback keep using the scoped tiers in CLAUDE.md; use this for the final gate.

## Dogfood loop: watch, steer, and log what you find
Yukta develops Yukta, so a run is also a test of Yukta. Watch it live instead of waiting for the end.
1. Start detached, then follow with the **Monitor** tool (one notification per event line):
   ```bash
   examples/11-dogfood/scripts/yukta-check.sh --detach [-m <module> | <gradle args>]
   #   STARTED execution=<e> session=<s>
   examples/11-dogfood/scripts/yukta-watch.sh <s> <e> --no-logs     # run under Monitor; drop --no-logs to see LOG lines
   ```
   Lines: `NODE <node> <status>`, `WORKFLOW <status>`, `CONTROL <cmd> applied=<bool>`, `LOG <node>: <line>`,
   `SLOW <node> …`, then the `RESULT` line. (`yukta-check.sh --watch …` does both in one.)
2. When a stage fails (`NODE <stage> FAILURE`/`TIMED_OUT`), read its report.
   - **Caused by your change:** fix it, then retry from that stage (earlier stages are not rerun) and
     Monitor the new execution id:
     `examples/11-dogfood/scripts/yukta-cli.sh workflow node retry <e> <stage>`
   - **A Yukta defect (not your change):** record it (below); do not fix it unless the user says "fix now".
3. Other levers, only on executions you started: `yukta-cli.sh workflow pause|resume <s> <e>`,
   `workflow node pause|resume|stop|skip <s> <e> <node>`, `workflow node signal <e> <node> --name N --decision D`,
   `workflow node breakpoint <e> <node> --field F --equals V [--clear]`, `workflow executions stop <e>`.
4. **Record every finding** in `<module>/docs/dogfood-backlog.md` (the module the defect is in; the dogfood
   tooling itself: `examples/11-dogfood/docs/dogfood-backlog.md`). Create the file with a `# Dogfood backlog`
   heading if it is missing, use the next free `DF-<n>` in that file, and append:
   ```
   ### DF-<n>: <one-line title>
   - Status: open        - Severity: P0–P3        - Kind: bug | error | perf | ux
   - Found: <date>, workflow <wf>, scope <scope>, execution <e>, stage <node>
   - Evidence: <the log / CONTROL / SLOW / RESULT line>
   - Repro: <command>
   ```
   Record: an unexpected `CONTROL … applied=false`; an exception or stack trace in a `LOG` line; a watcher
   that never ends or a RESULT that contradicts the node statuses; a CLI/server error; a `SLOW` line; a
   tooling failure (script, worktree, build setup). When fixed later, set `Status: fixed in <sha>`.
   Don't commit backlog edits unless asked; mention new entries in your reply.

## Server
If the script cannot reach a server (no `YUKTA_SERVER_URL`, no `.yukta-dev/current.env`):
```bash
./gradlew :cli:goBuild                                   # once
examples/11-dogfood/scripts/yukta-server.sh start <name>    # a server on a free port; prints "ready: export YUKTA_SERVER_URL=<url>"
examples/11-dogfood/scripts/yukta-server.sh stop <name>     # when finished (uses the provider that started it)
```
Always go through `yukta-server.sh`; never call a provider script or `bootRun` yourself. How the server is
launched is the *provider*, chosen by the environment, and nothing else in this skill depends on it:
- **worktree** (default, today): a git worktree of `origin/main` run with `bootRun`. Slow to start (Gradle
  build), so run `start` in the background and wait for "ready". Extra args pass through
  (`start <name> <base-ref>`, `stop <name> --force`).
- **native**: `YUKTA_SERVER_BIN=<path>` (or `YUKTA_SERVER_MODE=native`) runs the GraalVM executable
  (default `boot/build/native/nativeCompile/yukta`, from `./gradlew :boot:nativeCompile`). Starts in seconds, so
  no need to background it. Same state files and "ready" line, so everything below is unchanged.

Moving to the native executable later means setting `YUKTA_SERVER_BIN` (and, if you like, making `native` the
default in `yukta-server.sh`); this skill needs no other change. The checked-out code is what the workflow
runs Gradle on (`-p`), whichever provider serves it.

## CLI cheat-sheet
`YUKTA_SERVER_URL` selects the server; `-o json` for machine output.
- `yukta session apply <file|json>`, `session list`, `session get <id>`
- `yukta workflow start <session> <workflow>`, `workflow status <session> <exec>`, `workflow stop <session> <workflow>`
- `yukta logs <session> <exec>` streams until the execution finishes
- `yukta controlbus watch [--execution E] [--until-done] [--no-logs] [--type T]` streams control-bus events
- `yukta workflow node retry|signal|breakpoint <exec> <node>` need no session id
- `yukta plugin list`, `plugin get <type>`

## Don'ts
- Don't edit `.yukta-dev/` state by hand or kill servers you didn't start.
- Don't start servers any way other than `yukta-server.sh` (it keeps the state files the other scripts read).
- Don't run concurrent checks against the same checkout.
