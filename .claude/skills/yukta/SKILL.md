---
name: yukta
description: Use to verify changes in the Yukta repo (the final gate before a commit or PR, or any "run the checks / fix the build" request) by running its staged, self-healing `gradle check` workflow on a local Yukta server via the yukta CLI; the run repairs spotless, compile, test, coverage, style and spotbugs failures itself and reports what it healed. Also to watch a run and steer it (retry a stage, pause), record defects it finds, or start/stop a local Yukta server. Use instead of choosing gradle quality tasks yourself.
allowed-tools: Bash, Read
---

# Using Yukta to develop Yukta

The full `gradle check` is encoded as a Yukta workflow (`examples/11-dogfood/session.json`).
Do not decide which checks to run: run the workflow and act on its result.

## Token budget: follow a run by its problems, never its log
A run prints thousands of lines and almost all are noise. Every event on the control bus has a severity
(`INFO`, `WARN`, `ERROR`) and a failed node's event carries its reason, so ask for problems only:
- **Do:** `yukta-check.sh --watch …`, or `--detach` then `yukta-watch.sh <s> <e> --quiet`.
- **Don't:** `yukta-cli logs`, `yukta-cli controlbus watch` without `--quiet`, `yukta-check.sh` without
  `--watch`/`--detach` (it streams the whole log), or reading a report file whole.

A passing run is a couple of lines. A failing one adds only the cause, e.g.:
```
NODE style FAILURE
ERROR style: [ant:checkstyle] [ERROR] Foo.java:3: bad
ERROR style: Execution failed for task ':core:checkstyleMain'.
ERROR style: See the report at: file:///…/core/build/reports/checkstyle/main.html
WORKFLOW FAILURE
RESULT status=FAILURE failed_node=style execution=<id> session=<id>
```

## When to use which gate
- Inner loop (a few classes): the scoped Gradle tiers in CLAUDE.md, not this.
- Final gate before a commit or PR, or "make the build pass": `yukta-check.sh --watch` (self-healing).
  From Claude Code leave `--fixer` at `auto`: attempt 1 is a cheap fresh fixer, attempt 2 forks this session
  so it knows what you changed. Use `--fixer fresh` for a change you did not make (a different branch).
- You want the failure itself, not a repair (debugging a flaky stage, reproducing a CI failure): `--no-heal`.
- After a healed run: `git diff` the files the `HEAL` lines name, keep what is right, and commit it yourself.

## Run the checks
```bash
examples/11-dogfood/scripts/yukta-check.sh --watch        # checks the current checkout; exit 0 = SUCCESS
```
1. Read the final line: `RESULT status=<S> failed_node=<node> execution=<id> session=<id>`.
2. If `SUCCESS`: done (review `healed=`, see below). Otherwise the stage could not be healed: fix only what
   the `ERROR <failed_node>:` lines report (stages run in order: spotless, compile, tests, coverage,
   style, spotbugs, licenses, security, cli).
3. Fix, then rerun the script.
4. With `--no-heal`, formatting failures: run `./gradlew spotlessApply`, then rerun.
5. If the `ERROR` lines are not enough, open the report they name (stage table in
   `examples/11-dogfood/README.md`) but **grep it for the failures**, do not read it: `<error` in
   checkstyle/PMD XML, `<failure` or `<error` in `<module>/build/test-results/test/TEST-*.xml`.

Scope it when the whole project is overkill:
```bash
examples/11-dogfood/scripts/yukta-check.sh --watch -m core                       # staged gate for one module
examples/11-dogfood/scripts/yukta-check.sh --watch :core:test --tests '<FQCN>'   # any gradle args, one stage (failed_node=gradle)
```

For quick inner-loop feedback keep using the scoped tiers in CLAUDE.md; use this for the final gate.

## Self-healing runs (the default for the staged check)
`yukta-check.sh` rewrites the staged `gradle-check` so a failing stage repairs itself before the run
fails (`stage_workflows`/`run_stages` in `_lib.sh`, policy `examples/11-dogfood/heal.json`). Each stage is its
own workflow with explicit exits (SUCCESS, RETRY, ESCALATE, FAILED): a deterministic fix where one exists
(`spotless`: `spotlessApply`) makes the server's rule restart the stage; otherwise the driver runs up to two
headless Claude Code attempts (attempt 1 fresh, attempt 2 a fork of this session) and starts the stage again.
A final verify re-runs every task when anything was healed, so `SUCCESS` means the whole tree passes. `--no-heal` gives the plain chain, `--fixer fresh|fork|auto` picks who
fixes (a fork needs `CLAUDE_CODE_SESSION_ID`, i.e. a run started from Claude Code).
- **Don't edit files or run gradle in the checkout while a run is healing**: the fixer is editing it.
  Act on the final `RESULT` or an `ESCALATE:` only. `ERROR <stage>: …` lines before a `HEAL <stage> …`
  line are the failure being handled, not something for you to fix.
- `HEAL <stage> attempt=<n> mode=<auto|fresh|fork> fixed=<bool> files=<n> cost=$<x> summary=<text>` is
  one fix attempt. `summary=GUARD: …` means the attempt was rejected (it touched `config/`,
  `build-logic/` or `gradle/`, added a suppression, removed assertions, or changed nothing); its edits
  stay in the tree, so look at `git diff` and decide.
- `RESULT … healed=style:fix1,spotless:auto` lists what was fixed. **Review those changes** (`git diff`,
  records in `.yukta-dev/heal/<session>/`) before committing: the fixer never commits.
- A failure after healing (`failed_node=<stage>`, the stage's last recheck) is exhausted: take over, fix
  it, then rerun the script. Record a fixer that
  misbehaves (wrong fix, GUARD hit, cost over budget) as a `DF-<n>` finding.
- Workflows `stage-<stage>` with nodes `run`, `apply` and the outcome terminals `ok`, `retry`, `escalate`,
  `failed`; the output is `STAGE <stage> <outcome> execution=<e>` lines and `HEAL` lines.
- Budget: `YUKTA_HEAL_BUDGET` (USD per attempt, default 1), `YUKTA_HEAL_MODEL` (default sonnet). The
  server must be started with `YUKTA_PROCESS_ALLOW_ANY=true` (the dogfood server scripts do).

## Dogfood loop: watch, steer, and log what you find
Yukta develops Yukta, so a run is also a test of Yukta. Watch it live instead of waiting for the end.
1. Start detached, then hand the watching to the cheap **`yukta-run-watcher`** subagent (Haiku) so routine
   events never reach your context. Spawn it with `run_in_background` and the ids; it blocks until the run
   ends and returns one `RESULT` line, or a ≤12-line L1 triage (failed node, classification, key log lines,
   suggested next step) that you then act on. Stay silent on routine progress.
   ```bash
   examples/11-dogfood/scripts/yukta-check.sh --detach [-m <module> | <gradle args>]
   #   STARTED execution=<e> session=<s>   (the staged check: STARTED session=<s> log=<file>)
   ```
   Without the subagent, use **Monitor** with failures only (an event only on trouble, plus the end):
   `yukta-watch.sh <s> <e|log> --quiet` (only problems with their causes, the end, SLOW and RESULT).
   Lines: `NODE <node> <status>` (failed nodes), `ERROR <node>: <line>`, `WARN <node>: <line>`,
   `CONTROL <cmd> applied=false …`, `WORKFLOW <status>`, `SLOW <node> …`, then the `RESULT` line.
   (`yukta-check.sh --watch …` does both in one.)
   Drop `--quiet` (use `--no-logs`, then nothing) only when debugging a stage live. Never Monitor unfiltered
   `LOG` lines (compile warnings alone flood and rate-limit it).
   The subagent is read-only: you decide on retry, fixes and backlog entries. It triages at L1 only; when the
   cause isn't clear quickly (unknown, a Yukta stack trace, security, hang, repeat failure) it returns
   `ESCALATE:` at once with the evidence so far, and you take over the analysis.
   If the Go CLI (or a dogfood script) is what you analyse with and it falls short (missing flag, filter or
   output), improve it in `cli/` as part of the work (tests; `:cli:check` needs 100% function coverage)
   instead of working around it or only backlogging it.
2. Every failure or anomaly is analysed (root cause, not symptom) and either fixed or logged; never ignored.
   When a stage fails (`NODE <stage> FAILURE`/`TIMED_OUT`), start from its `ERROR` lines, then:
   - **Related to the current run/change** (your change broke it, or it blocks this run): fix it, then retry
     from that stage (earlier stages are not rerun) and watch the new execution id:
     `examples/11-dogfood/scripts/yukta-cli.sh workflow node retry <e> <stage>`
   - **Flaky/env** (e.g. JVM crash, port clash): retry once; if it recurs, treat it as a defect and record it.
   - **Unrelated Yukta issue** (not caused by this change, does not block the run): do not fix it inline;
     record it (below), then carry on.
   Also record unrelated things noticed while watching: functionality that doesn't work, monitoring/execution
   improvements, performance, and security issues.
3. Other levers, only on executions you started: `yukta-cli.sh workflow pause|resume <s> <e>`,
   `workflow node pause|resume|stop|skip <s> <e> <node>`, `workflow node signal <e> <node> --name N --decision D`,
   `workflow node breakpoint <e> <node> --field F --equals V [--clear]`, `workflow executions stop <e>`.
   Stop a run early, rather than letting it burn time, once a stage shows an `ERROR` you cannot be wrong
   about (`workflow executions stop <e>`).
4. **Record every finding** in `docs/internal/bugs.mdx`, under the `### Dogfood findings` heading of the
   module section the defect is in (the dogfood tooling itself: the `## Dogfood tooling` section). Create the
   heading if it is missing, use the next free `DF-<n>` on the whole page (`grep -o 'DF-[0-9]*'`), and append:
   ```
   #### DF-<n>: <one-line title>
   - Status: open
   - Severity: P0–P3
   - Kind: bug | error | perf | ux | security | improvement
   - Found: <date>, workflow <wf>, scope <scope>, execution <e>, stage <node>
   - Evidence: <the ERROR / WARN / CONTROL / SLOW / RESULT line>
   - Repro: <command>
   ```
   The page is MDX: put commands and log lines in backticks and escape `{`, `}` and `<` outside code.
   Record: an unexpected `CONTROL … applied=false`; an exception or stack trace in an `ERROR` line that is
   not from your change; a watcher
   that never ends or a RESULT that contradicts the node statuses; a CLI/server error; a `SLOW` line; a
   tooling failure (script, worktree, build setup); a monitoring/execution improvement or a security finding.
   When fixed later, set `- Status: fixed in <sha>`.
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
- `yukta-cli session apply <file|json>`, `session list`, `session get <id>`
- `yukta-cli workflow start <session> <workflow>`, `workflow status <session> <exec>`, `workflow stop <session> <workflow>`
- `yukta-cli controlbus watch --execution E --until-done --quiet` streams only problems with their causes (use this); without `--quiet` it streams every event (`--no-logs`, `--type T` narrow it)
- `yukta-cli logs <session> <exec>` streams the whole log until the execution finishes (humans; avoid)
- `yukta-cli workflow node retry|signal|breakpoint <exec> <node>` need no session id
- `yukta-cli plugin list`, `plugin get <type>`

## Don'ts
- Don't edit `.yukta-dev/` state by hand or kill servers you didn't start.
- Don't start servers any way other than `yukta-server.sh` (it keeps the state files the other scripts read).
- Don't run concurrent checks against the same checkout, and don't edit files or run Gradle in it while a
  self-healing run is going (the fixer is editing the same tree).
- Don't commit what a healing run changed without reading the diff; don't widen the guard (protected paths,
  suppressions) to get a run through, fix the cause or record a `DF-<n>`.
- Don't start a server without `YUKTA_PROCESS_ALLOW_ANY=true` (`yukta-server.sh` sets it) or run the fixer
  with `claude --bare` (it loses the login).
