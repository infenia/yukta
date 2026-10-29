---
name: yukta
description: Use for ANY build, compile, test, gradle/./gradlew, lint, spotless, checkstyle, PMD, SpotBugs, coverage, "does it pass" or pre-PR check work in the Yukta repo, from one test class to the whole gate. Runs Gradle (`yk gradle <args>`), the Go and UI tools (`yk tool -C <ui|cli> <command>`) and the self-healing gate for your changes (`yk check`) as Yukta workflows on the local dev server; each prints one RESULT line. Covers running a long check in the background, watching it and stopping it. Direct ./gradlew, go and pnpm/npm/npx commands are blocked in this repo (running the app with bootRun is not).
allowed-tools: Bash, Read, Monitor
---

# Build, test and check through Yukta

Every Gradle, Go and pnpm/npx run in this repo goes through a Yukta workflow on the dev server (built from `origin/main`,
`http://127.0.0.1:9858`, started on demand). `yk` is `examples/11-dogfood/yk` (on PATH after session start). A hook blocks
direct `./gradlew`, `gradle`, `go test|vet|build|fmt` and `pnpm|npm|npx` and names the `yk` command to use instead.

```bash
yk gradle <gradle args...>             # :core:compileJava | :core:test --tests '<FQCN>' | spotlessApply
yk tool [-C <dir>] <command> [args...] # dir relative to the repo root: -C cli go test ./... | -C ui node_modules/.bin/tsc --noEmit
yk check                               # the gate for your changes (below)
```
Every argument is shell-quoted. Two runs go at once (a Gradle daemon uses about 4 GB); a third waits.

## Pick the smallest scope
| Need | Command |
|---|---|
| Compile feedback | `yk gradle :core:compileJava :core:compileTestJava` |
| The tests you changed | `yk gradle :core:test --tests '<FQCN>'` (repeat `--tests`; a package: `'<package>.*'`) |
| Static analysis of a module | `yk gradle :core:checkstyleMain :core:pmdMain :core:spotbugsMain` |
| Format | `yk gradle spotlessApply` |
| Go / UI | `yk tool -C cli go test ./internal/...` / `yk tool -C ui npx --no-install vitest run` (also `eslint .`, `tsc --noEmit`, `prettier --check .`) |

## Check my changes
`yk check [--full] [--base REF] [--no-heal] [--models haiku,sonnet] [--triage off] [--detach]` is the gate. `yk plan` (same
flags, runs nothing) shows the stages: your changes, uncommitted and untracked files included, against the merge base with
`origin/main`; changed Gradle modules get every stage, their dependants compile and test, `cli/` and `ui/` get their own tools.
Stages run in turn, cheapest first (`--full`: everything unscoped, the gate before a PR).

A failing stage is **healed**: a free fix where it has one (`spotlessApply`, `make fmt`, `prettier --write`, `eslint --fix`),
then a local model rates the failure easy, average or hard, which picks where a ladder of fresh headless Claude sessions starts
(easy `haiku/low` then `sonnet/low`, average `haiku/high` then `sonnet/low`, hard `sonnet/low`). The stage is checked again after
every attempt. An attempt that changed no file ends the ladder, and `reuse`, `docs` and `actionlint` get one attempt. If anything
was healed, the Gradle tasks of all stages run once more. `licenses` and `security` are never healed. `--no-heal` stops at the
first failure (use it to see a failure as it is); `--triage off` starts every heal at the average level.

A Stop hook asks once, before you finish with uncommitted code changes, to run `yk check`.

**While a check runs, do not edit files or run Gradle in this checkout**: a fixer is editing it. When it ends, review everything
under `healed=` with `git diff`; fixers never commit.

## Long runs: background, watch, stop
A check takes minutes. Start it as a background task (`run_in_background`; the Bash tool also moves a foreground command there at
its timeout): its exit delivers the `RESULT` line. To follow it meanwhile, `yk check --detach` prints `STARTED execution=<e>
session=<s>`; run `yk watch <s> <e> --stages` under the Monitor tool: one `STAGE <name> finished` line per stage, plus `HEAL`,
`ERROR` and `RESULT` lines (a Monitor ends at its deadline, 5 minutes by default: start it again).

A foreground `yk` ends its run with itself: Ctrl-C, or stopping the background task, stops the execution and the runs below it.
A `--detach` run outlives its client and is not guarded against a second run: stop it with `yk stop <e> --tree` (a drain: a fixer
in progress gets up to 30 s). The `mcp__yukta__*` tools give the same data, but `get_workflow_status` and
`get_execution_tree` fail on a root run (#1300): use `yk tree` and `yk watch`.

## Diagnose a failed check, take over a run
- `failed_node` and the `OUTPUT <stage>:` lines say what failed. `yk tree <e>` shows which child run failed or is still going
  (`[current nodes]`, `paused`).
- A heal that gave up or was rejected: one line per attempt in the events log, newest last:
  `jq -c 'select(.event=="fix") | {stage,model,effort,fixed,files,cost,guard,summary}' ~/.local/state/yukta-dev/heal-events.jsonl | tail -8`
  (`guard` says why the guard rejected it). The same per attempt is in `.yukta-dev/heal/<session>/<stage>-<n>.json`, and the
  previous check's in `<session>.prev/`. Reading them is fine.
- Take over: `yk pause <e> --tree` lets the node that is running finish and holds the next one, so you can look at or fix the tree;
  a fixer session already running still finishes its edits. Then `yk resume <e> --tree` to continue or `yk stop <e> --tree` to
  give up. After fixing a failed stage by hand, rerun `yk check`: stages that pass again are quick.

## Read the result, not the log
The last line is `RESULT status=<S> failed_node=<node> healed=<stage:model/effort,..|-> execution=<e> session=<s> wall=<s> cost=$<heal spend> bytes=<shown to you>` (`yk stats` sums the runs); exit 0 only on
success. A failure (`status=FAILED|FAILURE`) is preceded by `ERROR …` lines and, for a check, `OUTPUT <stage>: …` (the last 40
lines of that stage). Fix only what they report, then rerun the same command. If they are not enough, grep the report they name
(`<failure` in `<module>/build/test-results/test/TEST-*.xml`); never read a report or log whole.
Levels of output: a green run is a few lines ending in `RESULT`; a red run adds the `ERROR` lines (known noise such as `BUILD FAILED in ..`, the exit-code line and JVM `WARNING:` lines is dropped, so a failing compile is a few hundred bytes), then a `MORE yk detail <session> <execution>` line just above `RESULT`. That is the next level: `yk detail <session> <execution> [--grep P] [--tail N] [--head N]` prints the full output of the stage that failed, filtered; use it before opening a report file.

## Headless fixers read more over MCP
A fixer's prompt carries the failure in short (the L1 digest) and its session id. With the dev server's read-only `fixer` key it can call
`mcp__yukta__get_workflow_history` / `get_node_output` (tail, grep, page) for more, so it asks for detail instead of receiving it. It cannot
start, control or change anything (a `VIEWER` key, plus a deny list in `examples/11-dogfood/scripts/_claude.sh`). `YUKTA_FIXER_MCP=off` disables it.

## Don'ts
- Don't run `./gradlew`/`gradle`, `go` or `pnpm`/`npm`/`npx` directly, or start the Yukta dev server yourself (`./gradlew bootRun`
  to try the app you are changing is fine).
- A second foreground `yk` run in the same folder is refused while the first runs.
- Don't edit anything under `~/.local/state/yukta-dev/` or `.yukta-dev/` by hand.
