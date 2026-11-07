# Dogfood: develop Yukta with Yukta

> How it works inside (workflows, TypeScript, heal, ticket pipeline): [ARCHITECTURE.md](ARCHITECTURE.md).

**Use case:** every build, test and check of this repo runs as a Yukta workflow on a local dev server, so Claude Code (or you)
reads one deterministic result line instead of choosing which checks to run. When a check fails, a repair workflow asks Claude
models, cheapest first, to fix it before a developer has to step in.

This file is the one guide: how to set it up, which commands exist, how heal works, how to operate and improve it. (The former `DESIGN.html` and
`RUNBOOK.md` were folded into it; the old design page is in git history.)

| I want to...                                        | Read                                                                   |
| --------------------------------------------------- | ---------------------------------------------------------------------- |
| get going                                           | [Setup](#setup), then [Commands](#commands)                            |
| understand what a failing `yk check` does           | [Self-healing check](#self-healing-check)                              |
| be told when a background run ends                  | [Telling the session](#telling-the-session---notify)                   |
| let open Claude sessions work my issues             | [Pickup](#pickup)                                                      |
| run an issue through the spec-to-PR pipeline        | [Ticket pipeline](#ticket-pipeline-work-in-progress)                   |
| use the Claude Code hooks, panes and slash commands | [Claude Code mod](#claude-code-mod-mod)                                |
| fix something that is off, tune costs               | [Operating](#operating)                                                |
| change dogfood itself                               | [Developing dogfood](#developing-dogfood), [Channel](#channel-channel) |

## How it works

```
origin/main ──build──▶ dev server :9858 (one per machine)        ~/.local/state/yukta-dev
                          ▲
any checkout/branch ──yk──┘  session apply → workflow start → problems-only watch → RESULT
```

- **One dev server per machine**, built from `origin/main` (`yk up`), listening on `127.0.0.1:9858`. It is a normal Yukta server that runs the `tool`,
  `check`, `heal`, `attempt`, `claude`, `pickup` and `ticket` workflows from `session.json`.
- **`yk` is the client** (`examples/dogfood/yk`, TypeScript on Bun). It applies the workflow library to a session for your checkout, starts a workflow
  with your working directory in the payload, watches it (problems only) and prints a single `RESULT` line.
- **Workflows come from your checkout, plugins from the server.** Editing `heal.json` or the generators changes what runs at once; a plugin
  change needs a merged commit and a server swap (`yk up`).
- **Blocked on purpose:** the `yukta-dogfood` mod (and `.claude/hooks/yk-hook.ts`) denies direct `./gradlew`, `go` and `pnpm|npm|npx` and names the `yk`
  command to use. `YUKTA_DOGFOOD=off` skips the hooks for one session.

## Setup

**Requires:** `git`, `bun` (everything here is TypeScript on Bun), `curl`, Java 25, Go (the CLI is built with the server).
**Optional:** `opengrep`, `trivy` (stages skipped when absent), `gh` + `cosign` for `yk up --release`, `mint` (docs links), `reuse`, `actionlint`,
Ollama with the triage model for the heal triage (without it heal starts at the average level).

```bash
examples/dogfood/yk up            # build origin/main (minutes the first time per commit) and start it
examples/dogfood/yk plan          # what a check of your changes would run
examples/dogfood/yk check         # run it; a failing stage is healed or ends the run
examples/dogfood/yk status        # what runs, stale or not (exit 3 when down)
```

`yk check`, `yk gradle` and `yk tool` start the server on demand. In Claude Code the SessionStart hook puts `yk` on PATH.

> **PATH matters.** The server inherits its PATH from whoever started it, and the ticket gate runs a bare `yk tool …`. Start it (or
> `yk pickup session`) only from a shell where `yk` is on PATH (Claude Code, or add `examples/dogfood` to PATH); otherwise a ticket run stops at the
> gate with `yk: command not found`.

### Keeping the server current

| Command                 | Does                                                                                                                                                                          |
| ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `yk up`                 | fetches `origin/main` into a managed worktree (`~/.local/state/yukta-dev/main`), builds `:boot:bootJar` and the CLI into `builds/<commit>/` (three are kept) and runs the jar |
| `yk up --release [tag]` | runs a published release instead of building                                                                                                                                  |
| `yk status`             | what runs, stale or not (exit 3 when down)                                                                                                                                    |
| `yk down`               | stops the server yk started                                                                                                                                                   |

When main moved, the server is swapped **only while no execution is running**; `yk check` and `yk gradle` complete a pending swap when idle.
The server runs any process, so it needs an API key: `yk` creates one per machine in `~/.local/state/yukta-dev/api-key` and sends it with every call.

## Commands

Every run ends with one line, exit 0 only on success:

```
RESULT status=<S> failed_node=<stage|-> healed=<stage:model/effort,..|-> execution=<id> session=<id> wall=<s> cost=$<heal spend> bytes=<shown to you>
```

Read it and the `ERROR` lines before it, not logs. Run the smallest scope that covers your change.

### Run

| Command                             | Does                                                                                                                                                                      |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `yk gradle [--detach] <args>`       | one Gradle run, e.g. `:core:test --tests X`. Any Gradle flag goes through verbatim (`--info`, `-x test`, `-Dk=v`, `--stacktrace`)                                         |
| `yk tool [--detach] [-C dir] <cmd>` | any other command once: `-C cli go test ./...`, `-C ui npx --no-install prettier --check .`. An environment variable goes through it too: `yk tool env K=V ./gradlew ...` |

`--detach` returns after `STARTED execution=.. session=..`; the run outlives the client. Without it, a foreground `yk` ends its run when interrupted
or stopped (Ctrl-C, or Claude Code stopping a background task). Add `--notify` to hear about the end ([Telling the session](#telling-the-session---notify)).

### Check

| Command                                                                                                                       | Does                                                                            |
| ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `yk plan [--full] [--base REF]`                                                                                               | dry run: the stages a scoped check of your changes would run (nothing executes) |
| `yk check [--full] [--base REF] [--stage a,b] [--skip a,b] [--no-heal] [--no-fast] [--models m,..] [--triage off] [--detach]` | the scoped, self-healing gate ([how it heals](#self-healing-check))             |

| `yk check` flag              | Meaning                                                                                               |
| ---------------------------- | ----------------------------------------------------------------------------------------------------- |
| `--full`                     | everything, unscoped: the gate before a PR                                                            |
| `--base REF`                 | compare against `REF` instead of the merge base with `origin/main`                                    |
| `--stage a,b` / `--skip a,b` | run only / leave out these stages                                                                     |
| `--no-heal`                  | stop at the first failing stage and show it as it is; edits nothing                                   |
| `--no-fast`                  | skip the single combined Gradle invocation that runs first when two or more Gradle stages are planned |
| `--models m,..`              | allow only these models (`haiku,sonnet`); others' rungs stop at once                                  |
| `--triage off`               | skip the triage; every heal starts at the average level                                               |
| `--detach`                   | return after `STARTED`; follow with `yk watch`                                                        |

**Scope.** The plan is your changes (uncommitted and untracked included) against the merge base with `origin/main`. It narrows the tests to the
changed classes (Tier 1) when only Java files changed, the diff touches no public/protected member or type declaration and every changed class has
its `<Class>Test`: no dependants, no coverage stage, the scope printed on the `# gradle:` line. Anything else runs whole modules plus dependants.

The Stop hook of the `yukta-dogfood` mod asks Claude Code once, before it finishes with uncommitted code changes, to run `yk check`; it stays quiet
when a `yk check` already passed on those changes or the changes were there at session start. With agent teams on, its `TaskCompleted` hook also
stops a teammate from completing a task while `yk check --no-heal` fails ([Agent teams](#agent-teams-experimental)).

### Observe

| Command                                                                  | Does                                                                                                  |
| ------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------- |
| `yk watch <session> <execution> [--all\|--stages]`                       | follow a run (problems only; `--stages` adds a `STAGE <name> finished` line per stage, for a Monitor) |
| `yk detail <session> <execution> [--grep P] [--tail N] [--head N]`       | the full output of the stage that failed (the `MORE` line says so)                                    |
| `yk tree [<execution>]`                                                  | which child run failed or is still going                                                              |
| `yk stats`                                                               | runs, failed, median wall and heal dollars per kind of run; fix rate and dollars per stage and rung   |
| `yk heal report\|show [--since 7d] [--run ID] [--project NAME] [--json]` | what heal did: where time and money went, what looks wrong, the raw events                            |
| `yk report [file [--yes]]`, `yk report scan\|on\|off\|status`            | failures captured (a non-success RESULT, a yk crash) and filed as GitHub issues once you approve it   |
| `yk bench [--runs N] [--max-ratio R] [--check] [gradle args]`            | the same Gradle work direct and through yk: `BENCH direct=.. yk=.. ratio=.. overhead=..`              |

`yk bench` takes the median of N runs after a warm-up and exits 1 above R (default 1.5) and +3 s. Measured: `:plugin-api:compileJava` 1.68 s direct,
2.05 s through `yk gradle` (ratio 1.22). `--check` compares `yk check --no-heal` with one direct run of the plan's tasks: 7.9 s against 25.3 s
(3.2x) for the staged chain, which the fast path (#1223) is for. `durations.tsv` keeps milliseconds.

### Steer

`yk tree|pause|resume|stop|skip|retry|break|step|signals|approve|reject`. `stop` is a drain of up to 30 s; add `--tree` to reach the runs below.
`pause` lets the running node finish and holds the next one. `approve|reject` answer a pending approval.

### Other

`yk pickup …` ([Pickup](#pickup)), `yk ticket …` ([Ticket pipeline](#ticket-pipeline-work-in-progress)). The older commands (`yk run issue|epic`, the
slash commands) are switched off while the loop is rebuilt; their pieces (and the previous guide) are in git history under `examples/11-dogfood/backup/`.

## Telling the session (`--notify`)

**What.** A detached run outlives the command that started it, so nothing told Claude when it ended. With `--detach --notify` (and in the mod's
`/yk-check`), `yk` posts the result into the Claude Code session it was started from. The message arrives as "Another Claude session sent a message":
`[yk] run finished: RESULT status=... ` plus up to 12 problem lines (each `APPROVAL` is posted at once).

**How.** Through [cross-session messaging](https://code.claude.com/docs/en/cross-session-messaging.md): Claude Code exports
`CLAUDE_CODE_MESSAGING_SOCKET` and `CLAUDE_CODE_MESSAGING_TOKEN` to the hooks and Bash commands of a session. After `STARTED`, a detached
`yk _notify` follows the run, and `notify_session` (`src/lib.ts`, the only place that knows the wire format: an auth line, then a
`{"type":"user",...}` line) writes to that socket. `.yukta-dev/notify-<execution>.log` is the notifier's output.

**Off switches and limits.**

- Not started from such a session (no socket), or `YUKTA_NOTIFY=off`: the run goes on without it.
- Needs a Claude Code with messaging on (v2.1.224 or later). Per the Claude Code docs, a session that bypasses permission prompts holds a message it
  cannot verify came from its own child process until you approve it (the token in the auth line is how it verifies one after the sender exited; only
  a session that prompts was tried here).
- The heal fixers have the socket variables removed (`src/heal/claude.ts`) and never post.
- A received message is a teammate's request, not your approval: it cannot answer a prompt.

## Self-healing check

`yk check` runs the stages of `yk plan` one after the other (cheap first). A failing stage goes to `heal`:

```
stage fails ─▶ 1 free fix ─▶ 2 baseline? ─▶ 3 triage ─▶ 4 ladder (fixer sessions) ─▶ 5 guard ─▶ stage re-run ─▶ 6 verify all Gradle stages
               (no model)    known failure   easy/avg/    haiku → sonnet                reject      pass: healed
                             on main: stop   hard         edits, never commits          bad edits   fail: next rung / FAILED
```

Steps 4 and 5 repeat per rung; the first six steps below are the order of events, not a list of options.

1. **Free fix**: `spotlessApply`, `make fmt`, `prettier --write`, `eslint --fix`, `make mod-tidy`. No model; the stage runs again.
2. **Baseline**: the failing stage runs once on a clean checkout of `origin/main` (kept in `.yukta-dev/baseline/<sha>`). When it fails
   there with the same problem lines, the stage is a known baseline failure: the heal ends with no fixer. No `origin/main`, a
   timeout or any other error means "not known" and the heal goes on.
3. **Triage**: a local model (System One, `tev1` via Ollama) rates the failure easy, average or hard. The label only picks the
   first rung; unreachable or unsure (< 0.3) means average. `--triage off` skips it. Optional `triage.stop` in `heal.json` (off by default) adds labels such as `environment` that end the heal with no fixer (`FAILED`, reason `ESCALATE (<label>): …`); enable one only after shadow-mode calibration (#1246) shows the router agrees with what fixed the failure.
4. **Ladder** (`heal.json`): easy `haiku/low → sonnet/low`, average `haiku/high → sonnet/low`, hard `sonnet/low`. One fresh
   Claude session per rung. An attempt that changed no file ends the ladder, and so does the first failed attempt on `reuse`, `docs` or `actionlint` (`heal.json` `singleAttempt`); the end of the ladder fails the stage. An attempt after which the stage fails exactly as it did after the attempt before (same problem lines, numbers aside) ends the ladder too: a stronger model would start from the same problems.
5. **Guard**: what changed on disk decides, not what Claude says. Rejected: edits to `config/`, `build-logic/`, `gradle/`,
   `.github/`, `.claude/` or this folder, added suppressions, removed assertions, test expectations changed with no code
   change, a claimed fix with no change. Fixers never commit.
   **MCP for fixers**: a fixer reads more of the failed run on demand instead of being handed all of it. claude.ts gives it the server's `/mcp` with the read tools only (`get_node_output`, `get_execution_tree`, `get_execution_logs`, `get_execution_result`, `get_workflow_status`, `get_workflow_history`, `get_workflow_details`); the mutating tools are denied in `claude.ts` and the key is a named `VIEWER` key (`fixer-key` in the dev state dir, added to `server.properties` when `yk up` starts the server, so a server started before this change needs a restart), which the server enforces. `YUKTA_FIXER_MCP=off` turns it off; another server needs `YUKTA_SERVER_URL` and a `VIEWER` key in `YUKTA_FIXER_MCP_KEY`. Without a key a fixer has no MCP, as before. `test/claude-mcp.test.ts` tests the arguments.
6. **Verify**: if anything was healed, the Gradle tasks of all stages run once more; a failure goes back to heal.

`licenses` and `security` are never healed. `--no-heal` ends the run at the first failing stage. With two or more Gradle stages planned, a `fast` step first runs all their tasks as one Gradle invocation (one start-up, one task graph); when it passes the Gradle stages are no-ops, when it fails the stages run one by one as before, so the failing stage, `failed_node` and the healing are unchanged. `--no-fast` skips it. `--models haiku,sonnet`
leaves a model out (its rungs stop at once).

### How a fixer is launched

Fixers run through `src/heal/claude.ts`: `--permission-mode auto --permission-prompts none` with a command
allowlist, the models pinned to `claude-haiku-5-5` and `claude-sonnet-5-5`, no user plugins, skills, MCP servers or CLAUDE.md,
a per-effort budget and time box. `YK_ADVISOR=sonnet` (experimental, off by default) gives a fixer an advisor model. `YUKTA_BUDGET_CHECK` (USD, default `checkBudget` in `heal.json`, $5) caps what all attempts of one check may cost together: once spent, no further rung starts and the stage ends as the ladder does. The prompt of an attempt carries 12-line excerpts around the `file:line` spots in the problem lines (up to 6). The free fix of `reuse` is `scripts/fix-reuse.ts` (the project header on every file `reuse lint` reports, a `.license` sidecar for types it cannot comment), so `healed=reuse:auto` costs no model call.

### Configuration

- `heal.json`: ladders per level, efforts (budget, time box), triage model and URL, the healable stages with their free fixes,
  the extra tools (ui, docs, reuse, actionlint). `YUKTA_TRIAGE_MODEL` and `YUKTA_TRIAGE_URL` override the triage model and URL.
- `fixer-rules.md`: the common system prompt of every fixer. `fixer-rules/<kind>.md` is appended for the failed stage (`style` → `pmd`, `spotbugs`, `tests` → `junit`, `coverage`, `compile`, `ui_vitest` → `vitest`, other `ui_*` → `ui`, `cli_*` → `go`); any other stage gets the common prompt alone. One constant file per kind, so the prompt cache holds. To add a role, add the file and a line in the `case` of `src/heal/claude.ts`.
- `session.json` is **generated**: after editing `heal.json`, run `bun src/heal/gen-session.ts` (through `yk tool -C examples/dogfood bun src/heal/gen-session.ts`), then commit it.
- Events of every heal step: `~/.local/state/yukta-dev/heal-events.jsonl`; records of one run: `<checkout>/.yukta-dev/heal/`.

## Pickup

Pickup hands GitHub issues to Claude Code sessions you keep open. You open one or more **pickup sessions**; a **schedule** (every 30 min) gives each
idle session the next issue assigned to you. By default the session does not implement the issue: it runs the **ticket pipeline** and answers its
questions on your behalf.

```
 GitHub issue (assigned to you, Ready)
        │  every 30 min: pickup workflow runs `pickup tick`
        ▼
 oldest idle session ◀── POST /v1/events ── channel (one per session, 127.0.0.1:<port>)
        │  <channel source="yukta-pickup" issue=..>
        ▼
 yk ticket run <n> --detach --notify ──▶ questions / result ──▶ input bridge ──▶ back to the same session
```

### Quick start

```bash
yk pickup session          # terminal 1..n: open a session, accept the development-channel dialog, leave it open
yk pickup list             # which issues would the next round take?
yk pickup on               # start the 30-minute schedule (or `yk pickup now` for one round)
yk pickup status           # schedule, bridge, sessions, last results
```

### Commands

| Command                                  | Does                                                                                                                               |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `yk pickup session [--name N]`           | Opens an interactive Claude Code session wired to a pickup channel. Starts the input bridge and the dev server if they are not up. |
| `yk pickup on`                           | Starts the schedule: workflow `pickup`, one execution that lives as long as the server. No-op if it already runs.                  |
| `yk pickup off`                          | Stops the schedule (and removes `pickup.exec`). Open sessions stay open but receive nothing.                                       |
| `yk pickup now [--to N] [--issue 12,34]` | Runs one round immediately, whether or not the schedule is on.                                                                     |
| `yk pickup list [--issue 12,34]`         | Prints the candidates of the next round, each `[free]` or `[taken: ...]`, and the filter in force. Changes nothing.                |
| `yk pickup status`                       | Prints the schedule state, the bridge, every registered session (`idle` or `busy with #n`) and the last 5 results.                 |
| `yk pickup bridge`                       | Runs the input bridge in the foreground. Normally you never call it: `session` starts it in the background.                        |

#### Parameters

| Parameter                      | Used by                 | Meaning                                                                                                                                                                              |
| ------------------------------ | ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `--name N`                     | `session`               | Session and channel name (default `yukta-pickup`). If a live session already has the name, the new one becomes `<name>-<pid>`.                                                       |
| `--to N`                       | `now`                   | Hand only to the session called `N` (same as env `YUKTA_PICKUP_ONLY`). Use it to test one session.                                                                                   |
| `--issue 12,34`                | `now`, `list`           | Consider only these issue numbers (same as env `YUKTA_PICKUP_ISSUES`). `#12`, `12 34` and `012` also parse; text without a number selects nothing, so a typo never lifts the filter. |
| file `$DEV_HOME/pickup-issues` | schedule, `now`, `list` | The same filter, persistent: numbers one per line, `//` starts a comment. A missing file or one with only comments means no filter. `--issue` wins over the file.                    |

`$DEV_HOME` is `~/.local/state/yukta-dev` (override: `YUKTA_DEV_HOME`).

### How a round works (`pickup tick`)

`src/pickup.ts tick` is what the schedule runs every 30 min (`SCHEDULE_TRIGGER`, `INTERVAL_MS` in `src/heal/gen-session.ts`) and what `now` runs once.

1. **Sessions.** Read the registry `$DEV_HOME/pickup/*.json` (`name`, `port`, `pid`, `started`). An entry whose pid is dead is deleted. Ask each live
   channel `GET /v1/status`: `busy: false` means idle. Idle sessions are ordered oldest first.
2. **Issues.** Query the Yukta Roadmap project: assigned to you **and** Ready, minus issues with an open PR naming them or an `issue/<n>` branch on
   the remote, then the `--issue` filter. Order: Priority (P0 first), then lowest number.
3. **Hand over.** Issue _i_ goes to idle session _i_, so with several of each one tick feeds several sessions. Each hand-over is a `POST /v1/events`
   (header `Authorization: Bearer <token>`, the content of `pickup-token`) with `{content, meta: {issue, url, title, via}}`; Claude sees it as
   `<channel source="yukta-pickup" issue=..>` and a turn starts. Output: one `PICKUP handed=#<n> to=<name>` line per issue.
4. **Skips** end with exit 0 and `PICKUP skipped=<why>`: no session is registered, all are busy (`all busy (a,b)`), or nothing is eligible.
   Only real errors (`gh` not signed in, a refused POST) exit non-zero.

The schedule is not counted by `busy()`, so it never blocks a server swap; `server_up` restarts it after a swap if it was on (`pickup.exec`).
The dev server runs the `session.json` of `origin/main`, so `yk pickup on` only finds the workflow once the change is merged and `yk up` swapped.

### The session and its channel

`yk pickup session` writes an MCP config (`$DEV_HOME/pickup-<name>.mcp.json`) for the `yukta-pickup` channel (the `pickup` profile of the generic
channel in `channel/`, TypeScript on Bun, see "Channel" below), creates `pickup-token` (mode 0600) if missing, and execs
`claude --name <name> --mcp-config <cfg> --dangerously-load-development-channels server:yukta-pickup`.

- Each channel listens on a free `127.0.0.1` port (`YUKTA_PICKUP_PORT` fixes it) and registers itself on start; the entry is removed when the session ends.
- It is **exclusive**: one event in flight. The session is _busy_ from the hand-over until Claude calls the `pickup_done` tool
  (`{issue, outcome: pr|blocked|skipped, url?, note?}`), which frees the channel and appends to `pickup.jsonl`. It also frees itself after 4 h
  (`YUKTA_PICKUP_BUSY_LIMIT`).
- A custom channel is not on Claude Code's allowlist, hence the development-channel flag and its dialog. `claude -p` ignores the flag, so
  sessions must be interactive and stay open.
- _Busy_ means busy **with a handed-over issue**. A session busy with your own work still counts as idle; its event queues until the turn ends.
  To keep a session out of pickup, do not open it with `yk pickup session`.
- A manual `/pickup <n>` works in any session and does not touch the channel.

### What the session does with an issue (`via`)

| `via`                         | Selected by                                                                  | The session...                                                                                                                                  |
| ----------------------------- | ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `ticket` (**default**, #1922) | nothing set                                                                  | is an orchestrator: runs `yk ticket run <n> --detach --notify`, decides the pipeline's questions, reports with `pickup_done`. Never edits code. |
| `direct`                      | env `YUKTA_PICKUP_VIA=direct` or the word `direct` in `$DEV_HOME/pickup-via` | implements the issue itself, following `.claude/skills/pickup/SKILL.md`.                                                                        |

In `ticket` mode the session never runs git by hand: `yk ticket worktree <n> [--in DIR]` (a worktree, or a branch in an existing one, reusing what
exists) and `yk ticket pr <n>` (push, draft PR) are its commands (`src/ticket/branch.ts`). After the PR opens the worktree is released (detached at
`origin/main`, marked free; `yk ticket release <n>` does it by hand) and the next ticket reuses it, so worktrees do not pile up. A run that
dead-ends keeps its worktree. The rules the session follows are in the `pickup` skill, section "Ticket pipeline mode".

`YUKTA_PICKUP_MODE=ticket` (or the word `ticket` in `$DEV_HOME/pickup-mode`) is a different thing: **no session at all**, the tick itself starts
`yk ticket run <n> --detach`, at most one run at a time. The output is `PICKUP handed=#<n> to=ticket`.

### The input bridge (`yk pickup bridge`, #1920)

One bridge per dev home (`bridge.pid`, log `bridge.log`; `session` starts it, `status` shows it). It follows the server's event feed and pushes
into the session that is busy with the issue. Without it no question or result reaches a session.

| Event                                | When                                                                                                    |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------- |
| `<channel kind=input request_id=..>` | a ticket-run question (route `ticket`) reaches the `session` tier, with the answering rules in its text |
| `kind=input` again                   | the question escalates to the `human` tier                                                              |
| `<channel kind=result>`              | the root `ticket` run ended; carries the `RESULT` line of `yk result` (SUCCESS also for a dead end)     |

These events do not take the busy slot. On every (re)connect the bridge announces the open requests again (the channel drops repeats).
The session answers over the Yukta MCP: `list_pending_inputs`, `answer_input`, `escalate_input`. Spec gates, PR gates and clarifications are
MEDIUM, so the session's agent key may answer them; the budget pause (`ask_pause<k>`) is HIGH, for a person only. With no owning session nothing
is sent; the UI and `yk cli inputs answer` still answer. Design: `design/plans/ticket-input-routing.md`.

### Safety

- The event carries only the number, title and URL. The skill reads the body with `gh` and treats it as untrusted.
- Only issues assigned to you and Ready on the project are handed over.
- No permission relay: the session keeps its own permission mode (`auto` recommended) and a prompt waits at the terminal.
  Never start it with `--dangerously-skip-permissions`.
- The channel listens on loopback only and every POST needs the bearer token.

### State files (`$DEV_HOME`)

| File                        | Content                                                       |
| --------------------------- | ------------------------------------------------------------- |
| `pickup/<name>.json`        | registry: one live session (`name`, `port`, `pid`, `started`) |
| `pickup-token`              | bearer token of the channels                                  |
| `pickup-<name>.mcp.json`    | generated MCP config of a session                             |
| `pickup.exec`               | execution id of the schedule; absent = off                    |
| `pickup.jsonl`              | one line per `pickup_done`                                    |
| `pickup-issues`             | optional issue filter                                         |
| `pickup-via`, `pickup-mode` | optional defaults for `via` and `mode`                        |
| `bridge.pid`, `bridge.log`  | the input bridge                                              |

### Troubleshooting

| Symptom                                                | Cause and fix                                                                                                                                             |
| ------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PICKUP skipped=no channel`                            | no pickup session is registered (`ls $DEV_HOME/pickup`): open `yk pickup session`                                                                         |
| `PICKUP skipped=busy with #n` for hours                | the session never called `pickup_done` (it frees itself after 4 h): tell the session to report, or restart it                                             |
| `PICKUP skipped=all busy (a,b)`                        | every session holds an issue; wait, or open another `yk pickup session`                                                                                   |
| a session you closed still shows in `yk pickup status` | it cannot: the entry goes with the session, and a dead pid is dropped by the next tick; `ls $DEV_HOME/pickup`                                             |
| one session never gets issues                          | it is not idle for the channel (busy flag), or it was opened without `yk pickup session`; `yk pickup now --to <name>` tests it                            |
| `yk pickup on` cannot find workflow `pickup`           | the dev server runs `origin/main`'s `session.json`; it appears after the pickup PR is merged and `yk up` swapped                                          |
| nothing is ever handed over                            | no issue is both assigned to you and Ready, or each has a PR or an `issue/<n>` branch; `yk pickup list` shows why                                         |
| the schedule vanished after a server swap              | `yk pickup status`; the next `yk` call starts it again when it was on                                                                                     |
| questions never reach the session                      | `yk pickup status` says the bridge is not running: `yk pickup session` or `yk pickup bridge`                                                              |
| ticket run `SUCCESS`, no PR, nothing implemented       | the server has no `yk` on PATH (`spec/gate.json` says `yk: command not found`): `yk down`, `yk up` from a shell with `yk` on PATH, `yk ticket resume <n>` |

Related: "The `pickup` profile" (channel environment variables) and "Ticket pipeline" below; tests: `yk tool -C examples/dogfood bun test pickup`.

## Ticket pipeline (work in progress)

Turns a GitHub issue into a reviewed, tested **draft PR**. An architect writes a spec and a test-case table; a test writer and an implementer work one
small step at a time inside hard guardrails; a reviewer signs off. Code (not a model) enforces order, gates, scope and budgets; models only write
specs, tests, code and reviews. Design: `design/plans/ticket-pipeline.md` (Epic #1872); open questions answered in
[Design notes](#design-notes-and-measurements-ticket-pipeline).

TypeScript on Bun: `yk tool -C examples/dogfood bun install`, tests with `yk tool -C examples/dogfood bun test`.

### Flow

```
issue ─▶ init ─▶ route ─▶ architect ─▶ validate-spec ─▶ test writer ─▶ gate-tests (red check) ─▶ [approval if high risk]
 (Ready,  branch   models   spec.json    code checks       test code     tests compile, fail     │
 criteria) worktree effort  + stubs                                       for the right reason    ▼
                                                       steps (waves, parallel when depends_on allows)
                                                       implement ─▶ validate-step ladder ─▶ commit-step
                                                           │ fail: retry ─▶ one tier up ─▶ architect revises spec ─▶ person
                                                           ▼
                                      full checks ─▶ reviewer (fresh context) ─▶ [approval] ─▶ finish: push issue/<n>, draft PR
```

| Role / component | Is                | Does                                                                                                             |
| ---------------- | ----------------- | ---------------------------------------------------------------------------------------------------------------- |
| Yukta workflows  | deterministic     | `ticket`, `attempt_round`, `steps_round`, `step`, `wave`, `finalize`, `escalate` (generated into `session.json`) |
| `ticketkit`      | deterministic CLI | schema validation, routing, scope checks, gates, failure digests, GitHub side (`src/ticket/ticketkit.ts`)        |
| Architect        | Claude            | spec, test-case table, interface stubs, revisions                                                                |
| Test writer      | Claude (cheaper)  | test code from stubs and the case table; never sees the ticket                                                   |
| Implementer      | Claude            | one step, constrained to the step's files                                                                        |
| Reviewer         | Claude            | fresh-context review against the acceptance criteria                                                             |
| Triage / cause   | local Ollama      | fuzzy classification only                                                                                        |

### Quick start

```bash
yk ticket new "Title" --criterion "Observable result 1" --criterion "Result 2" --file src/x.ts --ready   # file a ticket in the pipeline's shape
yk ticket run 123 --detach --notify     # run it: a tested change on branch issue/123 in ../yukta-issue-123
yk ticket status 123                    # which node runs or failed, last events
yk approve <execution>                  # answer an approval gate (or: yk reject <execution>)
```

A run ends with a draft PR (`Fixes #123`). It never merges and never pushes `main`. The run directory is `.yukta-dev/ticket/<run_id>/`.

### Commands (`yk ticket ...`)

| Command                                                                                                                                 | Does                                                                                                                                                                                                   |
| --------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `new <title> --criterion C.. [--goal G] [SIZING] [--role r=m/e..] [--label L..] [--ready] [--dry-run]`                                  | files an issue assigned to you in the shape the pipeline reads (`ticket-template.md`). `--dry-run` prints the body and the route; `--ready` also sets Status Ready on the project so `run` can take it |
| `edit <issue> [--title T] [--goal G] [--criterion C..] [SIZING] [--role r=m/e\|r=auto..] [--label L..] [--ready] [--force] [--dry-run]` | changes what you pass and keeps the rest of the body; `r=auto` (or `all=auto`) hands a role back to the router                                                                                         |
| `run <issue> [--local] [--detach [--notify]]`                                                                                           | runs the `ticket` workflow. `--local` uses this checkout's `ticketkit` (session `ticket-local`) instead of the dev server's main checkout                                                              |
| `status <issue\|execution>`                                                                                                             | the run's tree and its last events                                                                                                                                                                     |
| `resume` / `stop <issue\|execution>`                                                                                                    | continue a paused run / end it (a drain, like `yk stop --tree`)                                                                                                                                        |
| `worktree <issue> [--branch B] [--base REF] [--in DIR]`                                                                                 | a worktree or branch for the issue, reusing what exists                                                                                                                                                |
| `pr <issue> [--title T] [--result R] [--worktree W]`                                                                                    | push the branch and open the draft PR with `Fixes #n`                                                                                                                                                  |
| `release <issue>`                                                                                                                       | mark the issue's worktree free (detached at `origin/main`) for the next ticket                                                                                                                         |
| `supervisor`                                                                                                                            | install the dogfood supervisor of the ticket questions (`supervisor/*.json`)                                                                                                                           |
| `report [--json]`                                                                                                                       | first-try pass rate, cost share and cache hit ratio by role, prefix mismatches, BLOCKED rate (from `ticket-events.jsonl`)                                                                              |

`SIZING` = `--file PATH|GLOB.. --change KIND.. --open-decisions N`. Pickup runs this for you ([Pickup](#pickup)); `YUKTA_PICKUP_MODE=ticket` starts runs with no session.

### Writing a ticket

The body has four sections (`ticket-template.md`, parsed by `src/ticket/roles.ts`):

| Section                  | Content                                                                                                                                                                                         |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `## Goal`                | what and why                                                                                                                                                                                    |
| `## Acceptance criteria` | observable results; the run is `SKIPPED` (exit 3) without them. A criterion that asks for secrets, a push to main, other repos, settings or skipped checks **blocks** the intake                |
| `## Sizing`              | facts you read off the code: `- files:` paths or globs, `- change:` kinds (delete, rename, rewire, docs, config, new_logic, concurrency, schema, api_contract, security), `- open_decisions: N` |
| `## Pipeline roles`      | `- architect: opus/high` binds a model and overrides the router; a line ending in `(router)` only shows the router's choice and is ignored                                                      |

Sizing can only **raise** the route: `route` turns it into complexity and risk by fixed rules (file count, heavy kinds, ecosystems, modules,
`high_risk_paths`) and takes the higher of that and the static answer. An open decision puts the architect one effort up.

### Guardrails, retries and recovery

- **Step ladder.** Attempt 1 with the routed model; attempt 2 same model plus the failure digest; attempt 3 one tier up; after that the architect
  revises the spec; then a person. Mechanical failures (`scope_violation`, `locked_file_modified`, `patch_apply_fail`, `format_fail`) retry once,
  `infra_error`/`timeout` rerun once, a `blocked` step goes straight to a revision (design 9.1).
- **Budgets** (`project.json`): attempts per step 3, architect revisions per run 2, spec-validation retries 2, a cost cap per run by size (S $2, M $6, L $15) and a wall-clock cap (60, 120, 240 min)
  (pause, then a person). Step limits: `max_diff_lines`, `max_files_per_step`, `max_cases_total`, `max_test_lines`, `max_parallel`.
- **Locked tests.** After `gate-tests` the tests are locked in `spec/locked.json`; a step that edits them fails.
- **Recovery between stages.** Answers are schema-constrained (`--json-schema`), so most failures are answers that match the schema but that the next
  stage cannot use. Those go back to the stage that can fix them:
  - a spec the validator rejects goes to the next architect attempt with the errors; a policy conflict (a guard path, a file agents may not edit)
    becomes a question to a person (`validate-spec`);
  - a blocked test writer or a failed red check goes through `flow retry-spec` (the reason lands in `spec/downstream.json`, the worktree is reset);
  - the test writer checks its own files with `gate-tests --static`;
  - a step that fails the same way again after a revision goes to a person instead of a second revision.
- **Reading a failure.** The failed node's `ERROR` line names the cause: `blocked`, `schema_invalid`, `api_error`, `cli_crash`, `limit`.
- **Questions.** Every question to a person is an input request; with a pickup session it is answered over MCP ([Pickup](#the-input-bridge-yk-pickup-bridge-1920)).

### Source map

Everything is in `examples/dogfood`, next to `yk`: sources in `src/ticket/`, tests in `test/ticket/`, data in `schemas/`, `fixtures/`, `supervisor/`,
`project.json`, `ticket-template.md` and the generated `ticket-session.json`. `session.json` is generated: do not read or edit it.

| Path                                              | What                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `schemas/`                                        | JSON Schemas of every artifact the roles exchange (spec, step packet, step result, failure digest, revision, review, test-writer result)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `fixtures/<schema>/`                              | one `valid.json` and several `invalid-*.json` per schema                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `ticket-template.md`, `src/ticket/roles.ts`       | the ticket body the pipeline reads (Goal, Acceptance criteria, `## Sizing`, `## Pipeline roles`). **Sizing** (`src/ticket/sizing.ts`) holds facts the drafter reads off the code, `- files:` paths or globs, `- change:` kinds (delete, rename, rewire, docs, config, new_logic, concurrency, schema, api_contract, security), `- open_decisions: N`; init checks the files against the base tree and route turns them into complexity and risk by fixed rules (file count, heavy kinds, ecosystems, modules, `high_risk_paths`), taking the **higher** of that and the static answer: sizing never lowers a route. An open decision puts the architect one effort up. **Pipeline roles**: a bare `- architect: opus/high` line binds and overrides the routed model; a line ending in `(router)` only shows the router's choice at filing time and is ignored, so the run routes those roles itself (bodies filed before this rule carry five bare lines: `--role all=auto` hands them back). Sizing and roles count only on an issue assigned to the person running the pipeline. `yk ticket new <title> --criterion C.. [--file P..] [--change K..] [--open-decisions N] [--role architect=opus/high..] [--label L..] [--ready] [--dry-run]` files it with `gh`, assigned to you, and prints the `ROUTE` it will get (with notes on sizing paths missing from `origin/main`); `--dry-run` prints the body without filing; `--ready` sets Status Ready. `yk ticket edit <issue> [--title] [--goal] [--criterion..] [--file..] [--change..] [--open-decisions N] [--role r=model/effort\|r=auto..] [--label..] [--ready] [--force] [--dry-run]` changes only what is passed and keeps the rest of the body; refused once the issue's branch exists. The public `.github/ISSUE_TEMPLATE` is untouched |
| `project.json`                                    | the project profile: commands, conventions, high-risk paths, limits, budgets                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `src/ticket/ticketkit.ts`                         | the deterministic tools (`bun src/ticket/ticketkit.ts SUBCOMMAND`; schemas are validated with `ajv`): `validate-schema NAME FILE`; `init ISSUE` (run dir, branch, worktree; `SKIPPED` and exit 3 without acceptance criteria or when the branch exists); `route RUN_DIR` (models, effort, `tests_mode` into `routing.json`, with `basis`: the static and the sizing answer and why; a `route` event); `validate-spec RUN_DIR` (design 8.1 and the sizing lint 11.3; writes `spec/validation.json` and `spec/spec.hash`); `gate-tests RUN_DIR` (after the test writer; `--static` is the test writer's own self-check without the test run. Applies the stubs, checks only stubs and locked tests changed, `[T#]` tags, compile, a red check per case from the JUnit / Vitest / `go test -json` report, trivial-test lint; then locks the tests in `spec/locked.json` and writes `spec/test_map.json`); per-step tools in `src/ticket/stepkit.ts`: `pack-step` (one step's packet, byte-stable cache prefix, deny list), `scope-check` (the diff since the step base: write_files, locked hashes, suppressions, removed assertions, dependency files, forbidden imports, size), `regen` (generated files by command, never by hand), `free-fix` (formatters, never an attempt), `digest` (failure digest of at most 2 KB from `yk` output and JUnit XML), `event` (one `events.jsonl` line with token and cache fields from a claude result)                                                                                                                                                                                                                                                                                                                                                           |
| `fixer-rules/ticket-*.md`                         | the constant role files (`architect`, `test-writer`, `implementer`, `reviewer`), picked by `YK_ROLE` in claude.ts                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `src/ticket/call.ts`, `src/ticket/flow.ts`        | the glue of the workflows: `ticketkit call ROLE RUN` (one Claude call through `claude.ts --role`: prompt from files, answer validated with one retry, a crash or a transient API error (429, 5xx, overloaded) called again up to twice, a turn or budget cap and any other API error not; exit 2 needs clarification, 3 test writer blocked, 4 schema_invalid, 5 api_error, 6 limit; every call's raw answer kept; event line with tokens, cache fields and `outcome`) and `ticketkit flow preflight\|retry-spec\|step-begin\|validate-step\|commit-step\|full-checks\|close`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `src/ticket/ladder.ts`                            | failure routing and escalation (`ticketkit ladder decide                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | heal    | next-step | route-failure | build-revision                                                                                                                                                                                                                                                                                                                                                                                                    | apply-revision | review-verdict | need-approval | pause-packet | ack-pause | final-fail`): the retry ladder (a step starts on `implementer`, or `implementer_tricky`when the architect marked it`tricky` (`stepModel`in`src/ticket/lib.ts`); attempt 2 same model, attempt 3 one tier up, then the architect revises; short-circuits for BLOCKED, infra errors, licenses and security), heal for mechanical stages (everything outside `write_files`and any locked test is reverted and recorded),`baseline_failure`, revisions (completed steps immutable, spec v+1, changed cases only through the test writer), budgets (cost, wall clock, attempts, revisions: over a limit the run pauses with `human-packet.md`and waits for`yk approve | reject`) and the verdict override (a blocker or an unfaithful test turns APPROVE into CHANGES_REQUESTED) |
| `src/ticket/gen-ticket.ts`, `ticket-session.json` | generates the workflows `ticket`, `attempt_round`, `steps_round`, `step`, `finalize` and `escalate`; `--check` fails when the committed `session.json` is stale. `session.json` is generated: do not read or edit it                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `src/ticket/github.ts`                            | the GitHub side (design 15.9): `ticketkit github intake` (open, assigned, Ready, criteria; a criterion that asks for secrets, a push to main, other repos, settings or skipped checks BLOCKS, such text elsewhere in the issue is ignored), `claim`, `plan-comment`, `dead-end` (issue back to Ready + a comment, worktree stays), `finish` (drops the `[T#]` tags from the locked test names, format, commit, push `issue/<n>`, draft PR with `Fixes #n`: summary, changes per step, acceptance criteria with the reviewer's evidence, review verdict and findings, assumptions, the test cases with their tests, the final `RESULT` line, token usage per role (input, output, cache read and write, cost); fails without `Fixes #n` and `RESULT`). Never merges, never pushes main                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `yk`, `src/ticket/yk-ticket.ts`                   | `yk ticket run <issue> [--detach [--notify]]`, `yk ticket status\|resume\|stop <issue\|execution>` (also `examples/dogfood/yk ticket run 123`); approvals use `yk approve\|reject`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `src/ticket/waves.ts`                             | parallel step waves (design 11.2): `ticketkit waves next-wave                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | prepare | warm-wait | merge         | check`. Two ready steps with disjoint `write_files` run at once (`max_parallel`2, the`tool`group has two places), the second in its own worktree and branch from the same base, after the first has warmed the cache; they are merged in step order (a conflict is aborted and escalated to the later step) and a post-wave check runs on the merge.`ui/package.json`, the lockfiles and `shared_files` run alone |
| `src/ticket/report.ts`                            | `ticketkit report` / `yk ticket report`: first-try pass rate and mean attempts by (model, effort, complexity), cost share and cache hit ratio by role, `prefix_hash` mismatches, the BLOCKED rate (above 15% the steps are too small), how often Sizing raised the static complexity or risk                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `ticketkit flow mutation`                         | pitest on the changed classes at medium or high risk (never `:boot`, `:plus:*`, `:test-support`); survivors above `mutation.max_survivor_rate` (0.25) reach the reviewer as a finding                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |

### Tests (`test/`)

All offline, no API cost (fake `claude`, fake `gh`, throwaway git repos).

| Path                                                        | What                                                                                                                                                                                                             |
| ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `test/pickup.test.ts`                                       | offline test of pickup mode A: `YUKTA_PICKUP_MODE=ticket` (or `$DEV_HOME/pickup-mode`) makes `pickup tick` run `yk ticket run <n> --detach` instead of handing the issue to a session                            |
| `test/ticket/waves.test.ts`                                 | offline tests of waves, the mutation gate and the report (design 13.1 items 13, 20, 21; 15.13 item 31)                                                                                                           |
| `test/ticket/ladder.test.ts`, `test/helpers.ts`             | offline fault tests of the ladder, heal, revision, budgets and approvals (design 13.1 items 8, 10, 11, 12, 23; 15.13 items 27, 33) and the ladder rules                                                          |
| `test/ticket/ticket.test.ts`, `src/ticket/walk-workflow.ts` | offline test of the pipeline: walks the generated workflows against a throwaway repo, a fake `gh` and a fake claude (happy path, `BLOCKED`, invalid JSON retried once, failing gate, scope violation, high risk) |
| `test/ticket/schemas.test.ts`                               | offline test (fake claude, no API cost) of the schemas, fixtures and roles                                                                                                                                       |
| `test/ticket/spec.test.ts`                                  | offline test of `init`, `route` and `validate-spec` on a throwaway git repo (design 13.1 items 5, 18, 19, 22; 15.13 items 24, 29)                                                                                |
| `test/ticket/gate.test.ts`                                  | offline test of `gate-tests` with a fake runner (design 13.1 items 6, 14, 15, 16, 17)                                                                                                                            |
| `test/ticket/step.test.ts`                                  | offline test of the per-step tools (design 13.1 items 1-4, 7; 15.13 items 25, 26)                                                                                                                                |

## Claude Code mod (`mod/`)

Name: `yukta-dogfood` (`mod/.claude-plugin/plugin.json`, depends on the generic `yukta` mod). It keeps Claude Code on the `yk` rails: builds, tests and
checks go through Yukta, and a finished set of changes gets a `yk check`. It replaces the former `.claude/hooks/yk-hook.ts` settings hooks; the old
`statusLine` command is dropped.

### What it does

| Where                       | Does                                                                                                                                                           |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| session start               | records the uncommitted changes the session began with, starts the dev server (`yk up --background`), puts `yk` on PATH, tells Claude how to build             |
| before a Bash command       | denies `./gradlew`, `gradle`, `go test\|vet\|build\|fmt`, `pnpm\|npm\|npx` and names the `yk` command to use (`bootRun`/`nativeRun` pass) (`hooks/rewrite.ts`) |
| on a prompt                 | a prompt about building or testing gets a one-line pointer to the yukta skill                                                                                  |
| Stop                        | once per set of changes, asks Claude to run `yk check`; quiet when a `yk check` already passed on those changes or they were there at session start            |
| TaskCompleted (agent teams) | a **teammate** cannot complete a task while the shared checkout fails `yk check --no-heal` ([below](#agent-teams-experimental); `hooks/gate.ts`)               |
| `/yk-check`                 | runs `yk check --detach` and follows its stages in a pane with a Stop button                                                                                   |
| `/yk-plan`                  | prints `yk plan` in a pane                                                                                                                                     |

The enforcement (deny, Stop nudge) is also in `.claude/hooks/yk-hook.ts`, registered in `.claude/settings.json`, so it works without installing this
mod; the mod adds the panes and commands.

**Off:** `YUKTA_DOGFOOD=off` turns every hook off for a session (raw Gradle, your call). Headless fixers (`YUKTA_HEAL_FIXER=1`) are skipped.

### Install

```
/plugin install yukta-dogfood --marketplace infenia/yukta
```

In `/config` set the `yukta` mod's
`headers_command` `bash -c 'exec "$(git rev-parse --show-toplevel)"/examples/dogfood/yk mcp-headers'` (it gives the Yukta MCP server its key).

### Files (`mod/hooks/`)

| File                   | What                                                                             |
| ---------------------- | -------------------------------------------------------------------------------- |
| `register.tsx`         | registers the hooks, the commands and the panes (`hooks.json` lists it)          |
| `rewrite.ts`           | which build, test or lint command must run through `yk`, and how                 |
| `gate.ts`              | the message a teammate gets back when `yk check --no-heal` fails                 |
| `plan.ts`, `panes.tsx` | parsing of `yk plan` / `yk watch --stages` output, and the look of the two panes |
| `*.test.ts(x)`         | tests: `claude plugin test examples/dogfood/mod`                                 |

### Agent teams (experimental)

[Agent teams](https://code.claude.com/docs/en/agent-teams.md) need `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1` and an interactive lead. When a teammate
marks a task completed (or ends its turn with a task in progress), the mod's `classic.TaskCompleted` hook runs `yk check --no-heal` and, if it fails,
refuses the completion with the RESULT line and up to 12 problem lines; the teammate fixes and tries again. It is the team version of the Stop nudge.

- **Who and when.** Only a teammate's completion is gated (the lead's and a plain session's pass), and only with code changes (`git status` on the
  code globs). A tree a `yk check` already passed on (the `last-result` file) passes without another run.
- **`--no-heal` on purpose.** Teammates share one checkout, a healing check takes the checkout lock, and a fixer must not edit files under a team. It
  takes no lock and queues on the server, so teammates finishing together run their checks one after another. Healing stays with the lead: run
  `yk check` once all tasks are done.
- **Whole diff.** A failure can be another teammate's file: give each teammate its own files (the agent-teams advice anyway).
- **Fails open.** No RESULT line, a run over nine minutes or a `yk` that does not start never blocks a task. `YUKTA_DOGFOOD=off` turns it off.
- **Tested** offline (`gate.test.ts`, `register.test.tsx`), not with a live team: spawning teammates needs an interactive session.

## Channel (`channel/`)

Name: `yukta-channel`. A generic [Claude Code channel](https://code.claude.com/docs/en/channels-reference.md) (research preview): an MCP server over stdio
that Claude Code spawns, with an HTTP API on `127.0.0.1` in front of it. Anything that can make an HTTP call (a script, a webhook, a
workflow node, a chat bridge) can send an event into a running session and read what Claude sends back. [Pickup](#pickup) is built on it.

```
caller ── POST /v1/events ──▶ channel ── notifications/claude/channel ──▶ Claude Code session
caller ◀── GET /v1/stream ─── channel ◀── reply / done tools, permission prompts ── session
```

Bun and TypeScript; dependencies are `@modelcontextprotocol/sdk` and `zod` (`yk tool -C examples/dogfood/channel bun install`, lockfile `bun.lock`).

### Use it

**1. Register it** as an MCP server. The `env` goes in the entry: Claude Code gives an MCP server a filtered environment.

```json
{
  "mcpServers": {
    "yukta-channel": {
      "command": "bun",
      "args": ["run", "/abs/path/to/channel/src/main.ts"],
      "env": { "YUKTA_DEV_HOME": "/home/me/.local/state/yukta-dev", "YUKTA_CHANNEL_NAME": "yukta-channel" }
    }
  }
}
```

**2. Start Claude Code** with the development flag. A custom channel is not on the allowlist; the flag needs an interactive session (`claude -p` ignores it).

```bash
claude --mcp-config channel.json --dangerously-load-development-channels server:yukta-channel
```

**3. Send and listen.** The token is in `$YUKTA_DEV_HOME/channel-token` (created 0600 at the first start); the port is in the registry entry
`$YUKTA_DEV_HOME/channels/<name>.json`.

```bash
curl -s -X POST localhost:$PORT/v1/events -H "Authorization: Bearer $(cat $TOKEN)" \
  -d '{"content":"build failed on main","meta":{"severity":"high"}}'          # {"event_id":"3f9a1c2e"}
curl -sN localhost:$PORT/v1/stream -H "Authorization: Bearer $(cat $TOKEN)"    # what Claude answers
```

Claude sees `<channel source="yukta-channel" severity="high" event_id="3f9a1c2e">build failed on main</channel>` and answers with its
`reply` tool; the answer arrives on the stream as `event: reply`.

### HTTP API (under `/v1`; the full contract is `GET /v1/openapi.json`)

| Route                                              | Auth   | Does                                                                                                                                                                       |
| -------------------------------------------------- | ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /v1/status`                                   | no     | `connected`, `busy`, the event in flight, open permission prompts                                                                                                          |
| `GET /v1/openapi.json`                             | no     | OpenAPI 3.1                                                                                                                                                                |
| `POST /v1/events`                                  | bearer | `{content, meta?, sender?, idempotency_key?}` -> `202 {event_id}`; `200` duplicate; `400` invalid; `401`; `403` sender; `409` busy (exclusive); `413`; `503` not connected |
| `GET /v1/stream`                                   | bearer | server-sent events `event`, `reply`, `done`, `permission_request`, `permission_resolved`; `Last-Event-ID` or `?since=` replays the last 200                                |
| `GET /v1/permissions`, `POST /v1/permissions/{id}` | bearer | permission relay ([below](#permission-relay-optional))                                                                                                                     |

- `meta` keys become attributes of the `<channel>` tag and must be letters, digits and underscores (Claude Code drops others silently, so the API
  answers `400` instead). `event_id` and `sender` are set by the channel.
- Claude Code does not acknowledge events: a `reply` or `done` on the stream is the only delivery signal.
- Events queue in Claude Code while it is busy and arrive together on its next turn.

### Claude's side (MCP tools)

| Tool                                | Does                                                                                                                                                     |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `reply {text, event_id?, meta?}`    | a message for the caller; `event_id` is the attribute of the tag it answers                                                                              |
| `done {event_id?, outcome?, note?}` | the event is handled. On an **exclusive** channel it frees the channel (otherwise `POST /v1/events` answers `409` until `done`, or until the busy limit) |

### Permission relay (optional)

`YUKTA_CHANNEL_PERMISSIONS=on` declares `claude/channel/permission`: tool approval prompts (`Bash`, `Write`, ...) appear on the stream as
`permission_request` (`request_id`, `tool_name`, `description`, `input_preview`: untrusted text) and are answered with
`POST /v1/permissions/<request_id>` `{"behavior":"allow"|"deny","sender":"<id>"}`. The terminal dialog stays open and the first answer wins.
Anyone who can answer can approve a tool call, so it only starts with a `YUKTA_CHANNEL_SENDERS` allowlist, and only a listed sender can answer.

### Security

- The token is required for everything that sends or reads content; the server listens on `127.0.0.1` only.
- An ungated channel is a prompt injection path: the content of an event is untrusted input to Claude.
- `YUKTA_CHANNEL_SENDERS` (comma-separated ids) additionally requires `sender` on every event to be on the list: gate on the **sender**, not on a room or channel.

### Configuration (environment)

| Variable                     | Default                         | Meaning                                                        |
| ---------------------------- | ------------------------------- | -------------------------------------------------------------- |
| `YUKTA_CHANNEL_NAME`         | `yukta-channel`                 | MCP server name: the `source` of the tag and the registry name |
| `YUKTA_CHANNEL_INSTRUCTIONS` | generic text                    | what Claude is told about the events and tools                 |
| `YUKTA_CHANNEL_PORT`         | `0`                             | HTTP port; `0` takes a free one                                |
| `YUKTA_CHANNEL_EXCLUSIVE`    | off                             | `on`: one event in flight                                      |
| `YUKTA_CHANNEL_BUSY_LIMIT`   | `14400`                         | seconds after which an exclusive event frees itself            |
| `YUKTA_CHANNEL_SENDERS`      | none                            | allowed sender ids                                             |
| `YUKTA_CHANNEL_PERMISSIONS`  | off                             | `on`: permission relay                                         |
| `YUKTA_CHANNEL_TOKEN_FILE`   | `$YUKTA_DEV_HOME/channel-token` | the bearer token                                               |
| `YUKTA_DEV_HOME`             | `~/.local/state/yukta-dev`      | state: token, registry                                         |
| `YUKTA_CHANNEL_PROFILE`      | generic                         | `pickup`: the profile below                                    |

**Registry.** The channel registers `{name, port, pid, started}` in `$YUKTA_DEV_HOME/channels/<name>.json` (a live channel with the name makes the next
one `<name>-<pid>`) and removes it when the session ends.

### The `pickup` profile

`YUKTA_CHANNEL_PROFILE=pickup` is what `yk pickup session` starts: name `yukta-pickup`, exclusive, token `pickup-token`, registry `pickup/`, and one tool,
`pickup_done {issue, outcome: pr|blocked|skipped, url?, note?}`, which frees the channel and appends to `pickup.jsonl`. `src/pickup.ts tick` sends the
issue as `{content, meta: {issue, url, title}}`. Environment: `YUKTA_PICKUP_NAME`, `YUKTA_PICKUP_PORT`, `YUKTA_PICKUP_BUSY_LIMIT`.
A new profile is a name, a token and registry location, instructions, and optional tools (see `channel/src/pickup.ts`).

### Develop the channel

```bash
yk tool -C examples/dogfood/channel bun test      # unit tests (in-memory MCP client) and the stdio end-to-end test
yk tool -C examples/dogfood/channel bun check     # type check
yk tool -C examples/dogfood bun test pickup       # `pickup tick` against real channels
```

Paths relative to `examples/dogfood/channel/`:

| File                            | What                                                                                       |
| ------------------------------- | ------------------------------------------------------------------------------------------ |
| `src/channel.ts`                | the core: MCP server, event submission, exclusive mode, tools, permission relay, event bus |
| `src/http.ts`, `src/openapi.ts` | the HTTP API and its OpenAPI document                                                      |
| `src/registry.ts`               | the registry of running channels                                                           |
| `src/pickup.ts`                 | the pickup profile                                                                         |
| `src/main.ts`                   | the entry point and the environment                                                        |

## Design notes and measurements (ticket pipeline)

Reference material behind the decisions above; not needed to use the pipeline.

#### Answers to the VERIFY items

Resolves #1874 for `design/plans/ticket-pipeline.md` (Section 15 wins). Facts are from `main` at the time of writing; each answer names the file or command.

| #   | Question                             | Answer                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | Consequence                                                                                                                                                                                                                                                        |
| --- | ------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | How does `medium` effort map?        | `src/heal/claude.ts` passes `YK_EFFORT` (`low`, `medium`, `high`) straight to `claude --effort`, so the model side already supports `medium`. What is missing is the budget and time box: `heal.json` `efforts` has only `low` (600 s, $0.5) and `high` (1800 s, $2); `src/heal/gen-session.ts` builds its table from that key.                                                                                                                                                               | Add `"medium": {"timeoutSeconds": 1200, "budget": 1}` to `heal.json` (or a `ticket.json` of the new pipeline) in #1875. No code change.                                                                                                                            |
| 2   | Stage names                          | `yk plan --full` prints: `spotless compile tests coverage style spotbugs licenses security` (Gradle), `cli.fmt-check cli.mod-verify cli.vet cli.lint cli.sec cli.cover-check cli.vuln` (Go), `ui.tsc ui.eslint ui.vitest ui.headers ui.build` (UI, with `ui.prettier` when files changed), `reuse actionlint docs`. `--stage`/`--skip` treat `-` and `_` as the same (`filter_plan` in `src/lib.ts`), but not `.`: pass the name as `yk plan` prints it (`ui.tsc`, `cli.vet`).                | The doc's list in 15.5 is right for Gradle. The doc's `ui_*`/`cli_*` spellings must be written `ui.*`/`cli.*` in `--stage`; heal events and `RESULT healed=` print the underscore form (`ui_prettier`); the stage `style` is Checkstyle plus PMD on main and test. |
| 3   | Parallel wave                        | `SUB_WORKFLOW` has `concurrency` (default 1, up to 16, results keep message order) and a `SPLITTER` before it produces one message per step. A child uses its parent's execution slot.                                                                                                                                                                                                                                                                                                        | A wave is `SPLITTER` -> `SUB_WORKFLOW{concurrency: max_parallel}` -> `AGGREGATE`. No fixed branches needed. Gradle load is bounded by the `tool` concurrency group (2), not by this setting.                                                                       |
| 4   | Executor allowlist                   | The property is `yukta.process.allowed-commands` (exact executable names; empty means nothing runs), with `yukta.process.allowed-env` and `yukta.process.working-dir-root` (`docs/operations/configuration.mdx`). The dev server is started with `YUKTA_PROCESS_ALLOW_ANY=true` (`src/lib.ts`), so no list is needed for `yk`.                                                                                                                                                                | A hardened server must list the `ticketkit` entry script and claude.ts by the name used in the command. Noted here (#1879).                                                                                                                                        |
| 5   | `docs/api/openapi.json` regeneration | No task, script or workflow regenerates it. It is a hand-kept second snapshot: it differs from the tested `ui/openapi.json` only by deprecated flags on 15 legacy routes and its own `info`/`servers`/`security` (`design/plans/docs-sync/plan.md` line 38). `ui/openapi.json` is regenerated by `yk gradle :boot:test --tests "*OpenApiSnapshotTest" -Pyukta.openapi.update=true` and the types by `pnpm run api:types -- openapi.json` (`design/decisions/0001-input-requests.md` line 85). | `project.json` correctly lists only `ui/openapi.json` and the types as generated; add `docs/api/openapi.json` to `denied_read` (already) and to the scope-check deny list so a step cannot edit it. A docs step that needs it is high risk.                        |
| 6   | Cache tokens in `claude` output      | Yes. `src/heal/heal.ts` line 285 already reads `usage.input_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens` and `output_tokens` from the headless result.                                                                                                                                                                                                                                                                                                                   | Copy that jq into the `ticket-events.jsonl` writer (#1878, #1884).                                                                                                                                                                                                 |
| 7   | Per-test report for Vitest           | `ui/vitest.config.ts` already emits coverage `json`; for tests use `vitest run --reporter=json --outputFile=<file>` (a standard Vitest option; not used in the repo yet). JUnit XML for Gradle is in `<module>/build/test-results/test/`; Go: `go test -json`.                                                                                                                                                                                                                                | Gate-tests (#1877) reads these three; confirm the Vitest flags in its first test run.                                                                                                                                                                              |
| 8   | Merge policy                         | PRs are squash-merged and the PR title becomes the commit (`.github/WORKFLOWS.md` line 241, `CONTRIBUTING.md` line 59).                                                                                                                                                                                                                                                                                                                                                                       | Step commits on the run branch are free-form Conventional Commits; only the PR title matters.                                                                                                                                                                      |
| 9   | `plus/*` and Playwright              | `plan.ts` line 195: Playwright specs in `ui/e2e` run only in the full gate, not per step. `plus/` is `high_risk` in the profile and the mutation tool is disabled for `:plus:*`.                                                                                                                                                                                                                                                                                                              | Per-step gates skip e2e; a step touching `plus/**` or `ui/e2e/**` is high risk and needs the spec approval.                                                                                                                                                        |

##### Design changes

- 15.5 and 15.4: `docs/api/openapi.json` is not generated (item 5).
- 15.2 item 4: the wave is `SPLITTER` + `SUB_WORKFLOW` with `concurrency`, not two fixed branches (item 3).
- No server or CLI change is needed, so no extra issue is filed.

#### Measurements

##### Parallel steps against sequential steps (#1884)

Measured once on 2026-11-03 with `test/ticket/waves.test.ts` (the generated `ticket` workflow walked offline, a fake Claude, a 3 s step gate): two steps on disjoint files.

| Run                                                               | Wall clock |
| ----------------------------------------------------------------- | ---------- |
| two steps in a wave (parallel worktrees, merged, post-wave check) | 12 s       |
| the same two steps in sequence (the second depends on the first)  | 18 s       |

What this shows: the wave overlaps the first step's validation with the second step's implementation and validation, and the saving is the overlap. It does not include real model latency or a real Gradle gate; with a real gate of minutes the saving is larger, and the second step still waits for the first step's first implementer call (cache warm-up, design 11.1). Measure it again on a real ticket once real runs are allowed.

##### Sample report (10 offline runs, fake Claude)

`ticketkit report --events <ticket-events.jsonl>` on ten runs (five single-step, two parallel, one with a blocked step, one chain, one more single): first try 93% for haiku/medium/standard, cache hit ratio 89% per role (the fake reports 900 cache-read tokens in 1010), no `prefix_hash` mismatch, BLOCKED rate 7%. The numbers are those of the fake, not of a real model.

## Operating

Day-to-day running, tuning and repair of the dogfood loop.

### Where things are

| What                                                       | Where                                                                            |
| ---------------------------------------------------------- | -------------------------------------------------------------------------------- |
| the events log (all runs, all checkouts)                   | `$DEV_HOME/heal-events.jsonl` (`~/.local/state/yukta-dev/`), `YUKTA_HEAL_EVENTS` |
| the records of one run (attempts, outputs, exit codes)     | `<checkout>/.yukta-dev/heal/<session>/` (wiped by the next `yk check`)           |
| the heal policy (models, efforts, triage prompt, `giveUp`) | `heal.json`                                                                      |

### What costs money, what touches the outside

| Command                 | Spends                                                                                              | Touches                                                                                                        |
| ----------------------- | --------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `yk check`, `yk gradle` | Claude only when a stage fails and heal runs a fixer ($ per attempt: `heal.json` `efforts` budgets) | your working tree (fixers edit it, never commit)                                                               |
| `yk run issue\|epic`    | an implementer ($5 cap) and fixers                                                                  | the issue and the project board, a worktree, a pushed branch, a draft PR                                       |
| `yk heal report\|show`  | nothing                                                                                             | nothing (reads `heal-events.jsonl`; events written without a run id take the run of the session's next result) |

### The improvement loop

1. **Run it.** `yk check` (or let the Stop hook do it). Nothing to configure: events are on by default.
2. **Look.** `yk heal report --since 7d`. Read it top to bottom: _where the time goes_, the stage table (a slow stage is a slow
   check, not a heal problem), the heals (solved at which rung), the triage block, the cost per rung, the failures that came back.
3. **File what failed.** The Stop hook runs `yk report scan` (turn it off with `yk report off`, back on with `yk report on`, or `YUKTA_REPORT=off`). `yk report` lists the failures yk captured (a RESULT that was not SUCCESS, a yk crash); `yk report file` drafts one GitHub issue per new fingerprint, `--yes` files it (a repeat comments on the open issue, a closed one is filed again as a regression).
4. **Fix it, and measure.**
   - triage falls back or is slow: `yk heal show | jq 'select(.event == "triage")'` (`error`, `raw`), `ollama ps`, `journalctl -u ollama`;
     shorten the brief (`src/heal/heal.ts brief`);
   - triage rates too low or too high: change the prompt in `heal.json` and watch the `triage` events (`p`, the level and what fixed the stage) in `yk heal report`;
   - a fixer wastes money or wanders: read its `fix` events (`summary`, `files`, `guard`), fix `fixer-rules.md` or the guard;
   - the same failure keeps coming: fix the cause, or its check.

### Knobs

`heal.json` `giveUp` {`minAttempts`, `maxFixRate`, `days`}: a stage whose model attempts in the last `days` days were fixed at most `maxFixRate` of the time, after at least `minAttempts` of them, is not tried again (the check reports the failure; attempts that never started do not count; the free `auto` fix still runs); read from `YUKTA_HEAL_EVENTS`.
`YUKTA_HEAL_EVENTS` (file, or `off`), `YUKTA_HEAL_BUDGET` (USD per attempt, set per effort by `heal.json`), `YUKTA_TRIAGE=off` (skip the
System One call: heal starts at the default level), `YUKTA_TRIAGE_URL` (another Ollama, default `http://localhost:11434`), `YUKTA_TRIAGE_MODEL` (another System One model, default `heal.json` `triage.model`; see "Self-healing check" above), `YUKTA_BUDGET_ISSUE` (USD per issue run),
`yk check --models sonnet,opus`, `yk check --triage off`, `yk check --pause before-opus`.
For issue runs: `YUKTA_IMPL_MODEL` (opus), `YUKTA_IMPL_BUDGET` (5), `YUKTA_BUDGET_EPIC`, `YUKTA_WARM_DELAY`, `YUKTA_NAV_BACKENDS`,
`YUKTA_NAV_IDE`, `YUKTA_NAV_IDE_URL`, `YUKTA_NAV_CBM_BIN`, `YUKTA_NAV_CBM_CACHE`, `YUKTA_NAV_INDEX_TIMEOUT`.

### Troubleshooting

| Symptom                                            | Cause and fix                                                                                                                                                                                                                                                                   |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `triage-fallbacks` with `error`                    | the model is missing or in an unsupported format (`ollama list`, `ollama pull`), Ollama is down, or the call took over 60 s: Ollama cuts a System One call off at 60 s and the machine reads about 50 tokens a second without a big GPU, so keep the brief near 5000 characters |
| the triage always answers `average`                | the confidence floor (`minProbability` 0.3) sends vague answers to the default: look at `p` in the triage events                                                                                                                                                                |
| `yk check` says another healing check is running   | the watcher of the last run holds the lock for a few seconds; wait or `yk stop`                                                                                                                                                                                                 |
| no events                                          | `YUKTA_HEAL_EVENTS=off` is set, or the dev server was started before this change: `yk up`                                                                                                                                                                                       |
| `yk heal report` says no events for this selection | `--since`, `--run` or `--project` filter everything out                                                                                                                                                                                                                         |
| a fixer hit a guard (`guard-hits`)                 | it touched `config/`, `build-logic/`, `gradle/`, `.github/`, `.claude/`, the dogfood tooling, added a suppression or removed assertions; its edits stay in the tree: `git diff`                                                                                                 |
| the dev server runs old code                       | `yk up` rebuilds from `origin/main`; the workflows come from your checkout, the plugins from the server                                                                                                                                                                         |

### Gradle start-up (measured 2026-10-28, #1224)

- **One daemon.** Every `yk gradle` stage runs as a child of the dev server with the server's environment; after hours of runs next to an interactive session there was a single `GradleDaemon` process (4 h uptime, about 3.8 GB), so the stages reuse the daemon instead of starting one each. If `JAVA_HOME` or the JVM args of the server ever differ from your shell's, Gradle starts a second one: `yk gradle --status` lists them.
- **Unscoped no-op `spotlessCheck`: about 10 s** on a warm daemon (not the 4.6 s of the estimate: spotless itself walks every source set). A scoped stage (`:core:...`) stays near the 1.8 s of direct Gradle. The 2.5 s target does not hold for an unscoped run; scope it (`yk check` already does).
- **Configuration cache stays off.** `--configuration-cache` on the same task took about 40 s, four times slower, and logged `Stopping file watching and invalidating VFS after an error`: tasks of the build (the Go tasks of `cli`, per the comment in `gradle.properties`) are not compatible, so each run stores a new entry and then discards it. Fixing or excluding those tasks is a build change of its own, not part of the dev loop. The build cache (`org.gradle.caching=true`) is already on.

### Notes

- Sessions: one per checkout and command (`check-<branch>-<hash of the path>`, suffix `-plan` for `yk check`, `-gradle` for `yk gradle`/`yk tool`); the library is applied to it on every run, and the working directory travels in the start payload.
- One healing `yk check` per checkout at a time (`.yukta-dev/check.lock`; the refusal names the execution that holds it). `yk gradle`, `yk tool` and `yk check --no-heal` do not edit the checkout, take no lock and queue on the server (`yk tree` shows QUEUED).
- The previous README (issue and epic runs, heal analysis, the analyzers heal) is in git history before this change.

## Developing dogfood

Changing `yk`, heal, pickup or the ticket pipeline itself.

### Layout

`src/` holds the `yk` core (`yk.ts`, `runs.ts`, `plan.ts`, `lib.ts` and its parts `base`, `client`, `server`, `check`), `src/heal/` the self-healing check (`heal`, `claude`, `heal-report`, `fix-reuse`, `gen-session`) and `src/ticket/` the ticket pipeline (`ticketkit`, `ladder`, `flow`, `waves`, `gen-ticket`, `yk-ticket`, ...). `test/` mirrors it: `test/heal/`, `test/ticket/` (with `helpers.ts` and `fake/`), the rest next to `helpers.ts`.

| Path                                       | What                                                                                                                                                                                                                                                               |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `yk`                                       | the one entry point                                                                                                                                                                                                                                                |
| `session.json`                             | the five workflows: `tool`, `claude`, `attempt`, `heal`, `check` (generated)                                                                                                                                                                                       |
| `src/plan.ts`                              | the changes → stages, commands and free fixes                                                                                                                                                                                                                      |
| `src/heal/gen-session.ts`                  | generate the workflows into `session.json`                                                                                                                                                                                                                         |
| `src/heal/heal.ts`                         | the script steps of heal and attempt, and the guard                                                                                                                                                                                                                |
| `src/heal/claude.ts`                       | the slim headless Claude launcher                                                                                                                                                                                                                                  |
| `test/heal/claude.test.ts`                 | offline test of the MCP access of the fixers and that a stage gets its fixer role                                                                                                                                                                                  |
| `src/lib.ts`                               | server, session and watch helpers                                                                                                                                                                                                                                  |
| `test/heal/heal.online.test.ts`            | the end-to-end test of check and heal against a fake claude (`YK_ONLINE=1`, `yk up` first; no API cost) and `test/heal/heal.test.ts` the offline tests of the guard and the no-progress check                                                                      |
| `../../.claude/hooks/yk-hook.test.ts`      | the Claude Code hooks                                                                                                                                                                                                                                              |
| `test/notify.test.ts`                      | offline test of `notify_session` (auth and user line to a stub socket, off switches, never fails), the message text and the `--notify` flag check                                                                                                                  |
| `channel/`                                 | the generic Claude Code channel (Bun, TypeScript): an HTTP API to send events into a session and read Claude's replies, with permission relay; its `pickup` profile hands one GitHub issue at a time to an open session (`yk pickup session`). See "Channel" below |
| `src/pickup.ts`, `src/heal/gen-session.ts` | `tick` (hand the next assigned, Ready issue to the channel) and the generator of the `pickup` workflow in `session.json`                                                                                                                                           |
| `test/pickup.test.ts`                      | offline test of `tick` (real channel, fake `gh`), the ticket mode and `busy()` ignoring the schedule                                                                                                                                                               |
| `test/yk.online.test.ts`                   | interrupt, detach and `--stages` of `yk` against the dev server (`YK_ONLINE=1`, no cost)                                                                                                                                                                           |
| `package.json`, `tsconfig.json`            | the Bun package of `src/` and `test/` (`yk tool -C examples/dogfood bun test`)                                                                                                                                                                                     |

### Tests

```bash
yk tool -C examples/dogfood bun test            # offline: plan, heal steps, events, stats, notify, pickup, claude launcher, heal report, triage evaluation
yk tool -C examples/dogfood bun test             # offline: the ticket pipeline (schemas, spec, steps, gate, ladder, waves, decisions, the walked workflows)
yk tool -C .claude/hooks bun test                  # offline: the Claude Code hook (what is denied, what passes)
../../.github/scripts/plan-vs-ci.test.sh           # offline, also in CI: plan.ts and ci-tasks.sh pick the same modules
YK_ONLINE=1 yk tool -C examples/dogfood bun test heal.online yk.online   # on the dev server (yk up): a stopped foreground yk stops its run, --detach outlives, --stages;
                                                   # check and heal against a fake claude and a fake Ollama (about a minute, no API cost)
```

`test/heal/heal.online.test.ts` runs the scenarios through the real `check` workflow: two failing stages healed one after the other, a no-change attempt that
ends the ladder, an escalation, a failed verify that goes back to heal, and a triage that starts at `sonnet/low`. The tests of the switched-off
pieces (issue, epic, nav, steer) are in git history.

### Format and type check

`bun fmt` formats (settings in `bunfig.toml` `[fmt]`, skips in `.prettierignore`: generated `session.json` and the prompt files); `bun check` type checks like TypeScript 7 whatever `typescript` is installed (`bun -p process.versions.typescript`), `-p channel` for the one package with its own tsconfig. `yk check` runs both as the stages `dogfood.fmt` (free fix: `bun fmt`) and `dogfood.check` whenever anything under `examples/dogfood/` changes. Scripts: `bun run format`, `format:check`, `typecheck` (not named `fmt`: a project `fmt` script would replace `bun fmt`). Bun 1.4.4 has no `bun lint` yet (the docs at bun.com/docs/lint describe it), so there is no lint stage.
