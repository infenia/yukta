# yukta-dogfood (Claude Code mod)

Develops Yukta with Yukta. Needs the generic `yukta` mod (declared as a dependency). Replaces the former
`.claude/hooks/yk-hook.sh` settings hooks; the old `statusLine` command is dropped.

- session start: records the uncommitted changes the session began with, starts the dev server (`yk up --background`), puts `yk` on PATH, tells Claude how to build
- denies `./gradlew`, `gradle`, `go test|vet|build|fmt`, `pnpm|npm|npx` and names the `yk` command to use (`bootRun`/`nativeRun` pass)
- a prompt about building or testing gets a one-line pointer to the yukta skill
- Stop: once per set of changes, asks to run `yk check`
- `/yk-check` runs `yk check --detach` and follows its stages in a pane (Stop button), `/yk-plan` prints `yk plan`

Off: `YUKTA_DOGFOOD=off`. Headless fixers (`YUKTA_HEAL_FIXER=1`) are skipped.

## Install

```
/plugin install yukta-dogfood --marketplace infenia/yukta
```

In `/config` set the `yukta` mod's
`headers_command` `bash -c 'exec "$(git rev-parse --show-toplevel)"/examples/11-dogfood/yk mcp-headers'`.
