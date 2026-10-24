---
name: yukta
description: Use for ANY build, compile, test, gradle/./gradlew, check, lint, spotless, checkstyle, PMD, SpotBugs, coverage, "does it pass", "fix the build" or CI-failure work in the Yukta repo, at every scope from one test class to the full pre-commit gate. Runs it as a Yukta workflow on the local dev server (built from origin/main, port 9858) via `yk`, which prints one RESULT line; `yk check` also self-heals failing stages. Also for starting/stopping the dev server, watching, retrying or stopping a run, and filing dogfood findings. Direct ./gradlew build/test/check commands are blocked in this repo (running the app with bootRun is not).
allowed-tools: Bash, Read
---

# Build, test and check Yukta with Yukta

Every Gradle run in this repo goes through a Yukta workflow (`examples/11-dogfood/session.json`) on the
dev server: built from `origin/main`, on `http://127.0.0.1:9858`, shared by every checkout. `yk` is
`examples/11-dogfood/yk` (on PATH after session start). A hook blocks direct `./gradlew`, `gradle` and
`go test`; it names the `yk` command to use instead.

## Pick the smallest scope (CLAUDE.md test tiers)
| Need | Command |
|---|---|
| Compile feedback | `yk gradle :core:compileJava :core:compileTestJava` |
| Tests of the classes you changed | `yk gradle :core:test --tests '<FQCN>'` (repeat `--tests`) |
| A package / public contract | `yk gradle :core:test --tests 'com.infenia.yukta.<package>.*'` |
| A module's full gate | `yk check -m core` (repeat `-m` for several modules) |
| Final gate before commit/PR | `yk check` (once, at the end) |
| Spotless format | `yk gradle spotlessApply` |
| Go CLI | `yk gradle :cli:check` |

The server starts on demand (the first build of a new main commit takes minutes; the SessionStart hook
usually has it ready). Run long checks with `run_in_background` or `--detach` (below). Keep working in
the meantime only on a `yk gradle` or `yk check --no-heal` run, or in another checkout: a healing
`yk check` edits this checkout itself.

## Read the result, not the log
The last line is `RESULT status=<S> failed_node=<node> healed=<stage:step,..|-> execution=<e> session=<s>`;
exit 0 only on success. Before it come only problem lines (`NODE <node> FAILURE`, `ERROR <node>: …`,
`WARN …`, `HEAL …`, `SLOW …`). Fix only what the `ERROR <failed_node>:` lines report, then rerun the same
command. If they are not enough, grep the report they name (`<failure` in
`<module>/build/test-results/test/TEST-*.xml`, `<error` in checkstyle/PMD XML); never read a report or a
log whole, and never `yk cli logs` (it streams everything).

## Self-healing (`yk check`)
A failing stage repairs itself before the run fails: deterministic fix first (`spotlessApply`), then up
to a ladder of headless Claude attempts (haiku, sonnet, opus), then a verify pass. While it runs, don't edit files or run Gradle in
that checkout; act only on the final RESULT or an `ESCALATE:`. Review everything listed in `healed=`
with `git diff` before committing (the fixer never commits). `--no-heal` gives the plain fail-fast
chain (to see a failure as it is). Details, guards and budget: [reference/heal.md](reference/heal.md).

## Long runs: detach and watch cheaply
```bash
yk check --detach [-m mod]          # STARTED execution=<e> session=<s>   (yk gradle --detach: the same)
yk watch <s> <e>                    # blocks until the end: problems only, then RESULT
```
Hand the watching to the `yukta-run-watcher` subagent (Haiku, `run_in_background`) or the Monitor tool,
so routine events never reach your context. Steer only runs you started: `yk tree <e>`, `yk pause|resume|stop <e>
[--tree]`, `yk retry|skip <e> <node>`, `yk signals`, `yk approve|reject <e>`; when to use which:
[reference/control.md](reference/control.md).

## Dogfood: every anomaly is fixed or filed
A run is also a test of Yukta. Fix what your change broke; retry a flaky/env failure once; file
everything else (unexpected `CONTROL … applied=false`, Yukta stack traces, a watcher that never ends, a
RESULT contradicting node statuses, `SLOW`, a misbehaving fixer, tooling gaps) with `yk finding`.
If the CLI or `yk` falls short while you analyse, improve it as part of the work. Procedure, triage and
the finding format: [reference/dogfood-loop.md](reference/dogfood-loop.md).

## Server
`yk status` (exit 3 when down), `yk up` (start, or swap an idle server for a newer main),
`yk up --release [tag]` (run a published release instead), `yk down`. More, including state paths and
troubleshooting: [reference/server.md](reference/server.md).

## Don'ts
- Don't run `./gradlew`/`gradle` builds, tests or checks or `go test` directly, or start the Yukta dev
  server yourself (`java -jar`); `./gradlew bootRun` to try the app you are changing is fine.
- Don't run two checks in one checkout, or edit/build in it while a healing run is going.
- Don't commit healed changes unread; don't widen the fixer's guard to get a run through.
- Don't touch `~/.local/state/yukta-dev/` or `.yukta-dev/` by hand, or stop servers yk didn't start.
