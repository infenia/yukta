# yukta-dogfood (Claude Code mod)

Develops Yukta with Yukta. Needs the generic `yukta` mod (declared as a dependency). Replaces the former
`.claude/hooks/yk-hook.sh` settings hooks; the old `statusLine` command is dropped.

- session start: records the uncommitted changes the session began with, starts the dev server (`yk up --background`), puts `yk` on PATH, tells Claude how to build
- denies `./gradlew`, `gradle`, `go test|vet|build|fmt`, `pnpm|npm|npx` and names the `yk` command to use (`bootRun`/`nativeRun` pass)
- a prompt about building or testing gets a one-line pointer to the yukta skill
- Stop: once per set of changes, asks to run `yk check`
- TaskCompleted (agent teams, experimental): a **teammate** cannot complete a task while the shared checkout fails `yk check --no-heal`; see "Agent teams" below
- `/yk-check` runs `yk check --detach` and follows its stages in a pane (Stop button), `/yk-plan` prints `yk plan`

Off: `YUKTA_DOGFOOD=off`. Headless fixers (`YUKTA_HEAL_FIXER=1`) are skipped.

## Install

```
/plugin install yukta-dogfood --marketplace infenia/yukta
```

In `/config` set the `yukta` mod's
`headers_command` `bash -c 'exec "$(git rev-parse --show-toplevel)"/examples/11-dogfood/yk mcp-headers'`.

## Agent teams (experimental)

[Agent teams](https://code.claude.com/docs/en/agent-teams.md) need `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1` and an interactive lead. When a teammate
marks a task completed (or ends its turn with a task in progress), the mod's `classic.TaskCompleted` hook runs `yk check --no-heal` and, if it fails,
refuses the completion with the RESULT line and up to 12 problem lines; the teammate fixes and tries again. It is the team version of the Stop nudge.

- Only a teammate's completion is gated (the lead's and a plain session's pass), and only with code changes (`git status` on the code globs); a tree a
  `yk check` already passed on (the `last-result` file) passes without another run.
- `--no-heal` on purpose: teammates share one checkout, a healing check takes the checkout lock, and a fixer must not edit files under a team. It takes no lock
  and queues on the server, so teammates finishing together run their checks one after another. Healing stays with the lead: run `yk check` once all tasks are done.
- It checks the **whole diff**, so a failure can be another teammate's file: give each teammate its own files (the agent-teams advice anyway).
- It fails open: no RESULT line, a run over nine minutes or a `yk` that does not start never blocks a task. `YUKTA_DOGFOOD=off` turns it off.
- Test: `claude plugin test examples/11-dogfood/mod` (`gate.test.ts`, `register.test.tsx`). Not run with a live team: spawning teammates needs an
  interactive session.
