---
name: yukta
description: Use to verify changes in the Yukta repo by running its staged `gradle check` workflow on a local Yukta server via the yukta CLI, or to start/stop a dev Yukta server from a worktree. Use instead of choosing gradle quality tasks yourself.
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

For quick inner-loop feedback keep using the scoped tiers in CLAUDE.md; use this for the final gate.

## Server
If the script cannot reach a server (no `YUKTA_SERVER_URL`, no `.yukta-dev/current.env`):
```bash
./gradlew :cli:goBuild                                   # once
examples/11-dogfood/scripts/yukta-worktree.sh <name>        # worktree from main + server on a free port
examples/11-dogfood/scripts/yukta-stop.sh <name>            # when finished
```
Starting a server is slow (Gradle build); run it in the background and wait for "ready".

## CLI cheat-sheet
`YUKTA_SERVER_URL` selects the server; `-o json` for machine output.
- `yukta session apply <file|json>`, `session list`, `session get <id>`
- `yukta workflow start <session> <workflow>`, `workflow status <session> <exec>`, `workflow stop <session> <workflow>`
- `yukta logs <session> <exec>` streams until the execution finishes
- `yukta plugin list`, `plugin get <type>`

## Don'ts
- Don't edit `.yukta-dev/` state by hand or kill servers you didn't start.
- Don't run concurrent checks against the same checkout.
