---
name: yukta
description: Use for ANY build, compile, test, gradle/./gradlew, lint, spotless, checkstyle, PMD, SpotBugs, coverage or "does it pass" work in the Yukta repo, at every scope from one test class to a whole module. Runs Gradle (`yk gradle <args>`) and the Go and UI tools (`yk tool -C <ui|cli> <command>`: go test/vet, pnpm/npx lint, prettier, tsc, vitest) once as the `tool` Yukta workflow on the local dev server, which prints one RESULT line. Direct ./gradlew, go and pnpm/npm/npx commands are blocked in this repo (running the app with bootRun is not).
allowed-tools: Bash, Read
---

# Run Gradle, Go and UI tools through Yukta

Every Gradle, Go and pnpm/npx run in this repo goes through the `tool` Yukta workflow on the dev server (built from
`origin/main`, `http://127.0.0.1:9858`, started on demand). `yk` is `examples/11-dogfood/yk` (on PATH after
session start). A hook blocks direct `./gradlew`, `gradle`, `go test|vet|build|fmt` and `pnpm|npm|npx` and names the `yk` command to use.

```bash
yk gradle <gradle args...>      # e.g. :core:compileJava   |   :core:test --tests '<FQCN>'   |   spotlessApply
yk gradle --detach <args>       # STARTED execution=<e> session=<s>; follow it with: yk watch <s> <e>
```

```bash
yk tool [--detach] [-C <dir>] <command> [args...]   # dir relative to the repo root, default the root
yk tool -C cli go test ./...
yk tool -C ui npx --no-install prettier --check .   # or: node_modules/.bin/tsc --noEmit
```

Every argument is shell-quoted, so arguments with spaces work. Gradle and tool runs share two slots
(each Gradle daemon uses about 4 GB); a third waits its turn.

## Pick the smallest scope
| Need | Command |
|---|---|
| Compile feedback | `yk gradle :core:compileJava :core:compileTestJava` |
| Tests of the classes you changed | `yk gradle :core:test --tests '<FQCN>'` (repeat `--tests`) |
| A package | `yk gradle :core:test --tests 'com.infenia.yukta.<package>.*'` |
| A module's static analysis | `yk gradle :core:checkstyleMain :core:pmdMain :core:spotbugsMain` |
| Format | `yk gradle spotlessApply` |
| Go CLI | `yk gradle :cli:check`, or one command: `yk tool -C cli go test ./internal/...` |
| UI lint / types / format / tests | `yk tool -C ui <command>` (`eslint .`, `tsc --noEmit`, `prettier --check .`, `vitest run`) |

## Check my changes
`yk check [--full] [--base REF] [--no-heal] [--models haiku,sonnet,opus] [--triage off] [--detach]` is the gate: it
plans the stages of your changes (below) and runs them one after the other through `tool`, cheapest first.
A failing stage is **healed**: its free fix first where it has one (`spotlessApply`, `make fmt`, `prettier --write`),
then a small local model (System One) rates the failure easy, average or hard, which picks the model and effort to
start with (haiku/medium, sonnet/medium, sonnet/high); fresh headless Claude sessions climb effort and model from
there, and the stage is checked again after every attempt. While it runs, do not edit files or run Gradle in this
checkout. `licenses` and `security` are never healed. Act only on the final line: `RESULT status=SUCCESS healed=<stage:step,..>`
(review everything under `healed=` with `git diff`; the fixer never commits) or `status=FAILURE|FAILED failed_node=<stage>`,
preceded by `OUTPUT <stage>: ...` (the last 40 lines of that stage). `--no-heal` stops at the first failure instead (use it
to see a failure as it is, or to keep editing); `--triage off` starts every heal at sonnet/medium. `--full` runs everything
unscoped (the gate before a PR).

## What would a check of my changes run?
`yk plan [--full] [--base REF]` is a dry run: it maps your changes (against the merge base with `origin/main`,
uncommitted and untracked files included) to stages and prints one `stage<TAB>dir<TAB>command` line each,
running nothing. Changed Gradle modules get every stage, the modules that depend on them compile and test; all of
`cli/` runs when anything in it changed; the UI runs eslint, prettier and `vitest related` on the changed files and
`tsc` on the whole package. Run the lines you need with `yk tool -C <dir> sh -c '<command>'` (or `yk gradle`).

## Read the result, not the log
The last line is `RESULT status=<S> failed_node=<node> healed=- execution=<e> session=<s>`; exit 0 only on
success. A failed command gives `status=FAILED failed_node=tool`, preceded by `ERROR run: …` lines carrying
the command's own output. Fix only what those lines report, then rerun the same command. If they are not enough,
grep the report they name (`<failure` in `<module>/build/test-results/test/TEST-*.xml`); never read a report
or log whole.

## Don'ts
- Don't run `./gradlew`/`gradle`, `go` or `pnpm`/`npm`/`npx` commands directly, or start the Yukta dev server
  yourself; `./gradlew bootRun` to try the app you are changing is fine.
- One `yk` run at a time per folder: a second one is refused while the first runs.
- Don't touch `~/.local/state/yukta-dev/` or `.yukta-dev/` by hand.
