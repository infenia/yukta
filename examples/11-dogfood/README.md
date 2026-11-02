# 11 — Dogfood: develop Yukta with Yukta

**Use case:** every build, test and check of this repo runs as a Yukta workflow on a local dev server, so Claude Code (or you)
reads one deterministic result line instead of choosing which checks to run. When a check fails, a repair workflow asks Claude
models, cheapest first, to fix it before a developer has to step in.

The design, as built, is in [DESIGN.html](DESIGN.html): the workflows, the heal ladder, the guard, the fixer session, the
configuration and the engine gaps found on the way. This file is the short operating guide.

```
origin/main ──build──▶ dev server :9858 (one per machine)        ~/.local/state/yukta-dev
                          ▲
any checkout/branch ──yk──┘  session apply → workflow start → problems-only watch → RESULT
```

## Commands

| Command | Does |
|---|---|
| `yk gradle [--detach] <args>` | one Gradle run, e.g. `:core:test --tests X` |
| `yk tool [--detach] [-C dir] <cmd>` | any other command once: `-C cli go test ./...`, `-C ui npx --no-install prettier --check .` |
| `... --detach --notify` (`yk check`, `yk gradle`, `yk tool`) | after `STARTED`, a detached `yk _notify` follows the run and posts into the Claude Code session that started it: each `APPROVAL` at once, and when the run ends the `RESULT` line, the problem lines and `yk detail` (a turn starts if the session is idle). See "Telling the session" below |
| `yk watch <session> <execution> [--all\|--stages]` | follow a run (problems only; `--stages` adds a `STAGE <name> finished` line per stage, for a Monitor) |
| `yk plan [--full] [--base REF]` | dry run: the stages a scoped check of your changes would run (nothing executes) |
| `yk check [--full] [--base REF] [--stage a,b] [--skip a,b] [--no-heal] [--no-fast] [--models m,..] [--triage off] [--detach]` | the scoped, self-healing gate |

Any Gradle flag goes through `yk gradle` verbatim (`--info`, `-x test`, `-Dk=v`, `--stacktrace`); an environment variable or a folder goes through `yk tool` (`yk tool env K=V ./gradlew ...`, `yk tool -C sub cmd`). `YUKTA_DOGFOOD=off` in the environment skips the mod's hooks for one session (raw Gradle, the user's call).

`yk plan` narrows the tests to the changed classes (Tier 1) when only Java files changed, the diff touches no public/protected member or type declaration and every changed class has its `<Class>Test`: no dependants, no coverage stage, the scope printed on the `# gradle:` line. Anything else runs whole modules plus dependants; run `yk check --full` before a PR.

`yk bench [--runs N] [--max-ratio R] [--check] [gradle args]` runs the same Gradle work direct and through yk and prints `BENCH direct=.. yk=.. ratio=.. overhead=..` (median of N after a warm-up; exit 1 above R, default 1.5, and +3 s). Measured: `:plugin-api:compileJava` 1.68 s direct, 2.05 s through `yk gradle` (ratio 1.22). `--check` compares `yk check --no-heal` with one direct run of the plan's tasks: 7.9 s against 25.3 s (3.2x) for the staged chain, which the fast path (#1223) is for. `durations.tsv` keeps milliseconds.

Every run ends with `RESULT status=<S> failed_node=<stage|-> healed=<stage:model/effort,..|-> execution=<id> session=<id>`; exit 0
only on success. `yk watch <session> <execution>` follows a detached run and prints problems only. Run the smallest scope
that covers your change.

The Stop hook of the `yukta-dogfood` Claude Code mod (`examples/11-dogfood/mod`) asks Claude Code once, before it finishes with uncommitted code changes, to run `yk check`; it stays quiet when a `yk check` already passed on those changes or the changes were there at session start. `YUKTA_DOGFOOD=off` turns the mod's hooks off. With agent teams on, its `TaskCompleted` hook also stops a teammate from completing a task while `yk check --no-heal` fails (`mod/README.md`, "Agent teams").

Steering: `yk tree|pause|resume|stop|skip|retry|break|step|signals|approve|reject` (`stop` is a drain of up to 30 s; add `--tree` to reach the runs below). A foreground `yk` ends its run when it is interrupted or stopped (Ctrl-C, or Claude Code stopping a background task); `--detach` runs outlive their client.

The other commands (`yk run issue|epic`, steering, the slash commands) are
switched off while the loop is rebuilt; their pieces are in `backup/`, and `backup/README.md` says how to bring each back.

## Telling the session (`--notify`)
A detached run outlives the command that started it, so nothing told Claude when it ended. With `--detach --notify` (and in the mod's `/yk-check`),
`yk` posts the result into the Claude Code session it was started from, through [cross-session messaging](https://code.claude.com/docs/en/cross-session-messaging.md):
Claude Code exports `CLAUDE_CODE_MESSAGING_SOCKET` and `CLAUDE_CODE_MESSAGING_TOKEN` to the hooks and Bash commands of a session, and
`notify_session` (`scripts/_lib.sh`, the only place that knows the wire format: an auth line, then a `{"type":"user",...}` line) writes to that socket.
The message arrives as "Another Claude session sent a message": `[yk] run finished: RESULT status=... ` plus up to 12 problem lines.
Not started from such a session (no socket), or `YUKTA_NOTIFY=off`: the run goes on without it. `.yukta-dev/notify-<execution>.log` is the notifier's output.
Limits: it needs a Claude Code with messaging on (v2.1.224 or later); per the Claude Code docs, a session that bypasses permission prompts holds a message it cannot verify came from its own child process
until you approve it (the token in the auth line is how it verifies one after the sender exited; only a session that prompts was tried here); the heal fixers have the socket
variables removed (`scripts/_claude.sh`) and never post. A received message is a teammate's request, not your approval: it cannot answer a prompt.
## Pickup session (channel, research preview)
`yk pickup session [--name N]` opens an interactive Claude Code session (default name `yukta-pickup`) that spawns the `yukta-pickup` channel
(the `pickup` profile of the generic channel in `channel/`, TypeScript on Bun: see `channel/README.md`). Open as many as you like, each in its own terminal or worktree. Each channel listens on a free
`127.0.0.1` port (`YUKTA_PICKUP_PORT` fixes it) and registers itself in `$DEV_HOME/pickup/<name>.json` (name, port, pid), removing the entry
when the session ends; a second session with a live name becomes `<name>-<pid>`. A POST to its `/v1/events` (header `Authorization: Bearer <token>`, the content of
`pickup-token` in the dev state dir) becomes a `<channel source="yukta-pickup" issue=..>` event that starts a turn; Claude ends the issue
with the `pickup_done` tool, which frees the channel (it also frees itself after 4 h). `GET /v1/status` shows `busy`. A custom channel is not on
the allowlist, so each session starts with `--dangerously-load-development-channels` and its dialog; `claude -p` ignores that flag, so the
sessions must be interactive and stay open.

`yk pickup on|off|now [--to N]|status` drives the schedule: workflow `pickup` (`SCHEDULE_TRIGGER`, every 30 min, `INTERVAL_MS` in
`scripts/gen-pickup.py`) runs `scripts/pickup.sh tick`. A tick reads the registry (an entry whose pid is dead is deleted), asks each session
for its `/status`, and hands the next issue to the **oldest idle session**: the first assigned, Ready issue by Priority (P0 first) with no open
PR naming it and no `issue/<n>` branch on the remote. With several idle sessions and several such issues one tick hands one issue to each
(`PICKUP handed=#<n> to=<name>`, one line per issue). It skips (`PICKUP skipped=<why>`, exit 0) when no session is registered, all are busy
(`all busy (a,b)`), or nothing is eligible. `now` runs one tick at once; `now --to <name>` (or `YUKTA_PICKUP_ONLY`) hands only to that session.
`yk pickup status` lists the sessions: name, port, idle or `busy with #n`. The schedule is one execution that lives as long as the server:
`busy()` does not count it, so it never blocks a swap, and `server_up` starts it again after a swap when it was on (`pickup.exec` in the dev
state dir). The dev server runs the `session.json` of `origin/main`, so `yk pickup on` finds the workflow only once this is merged. The skill
that works an issue is `.claude/skills/pickup/SKILL.md`.

**Which session gets the issue.** The oldest idle one, or the one you pin. A session counts as busy only while it holds an issue the channel
handed over: one busy with your own work is still idle for the channel, and its event waits until its current turn ends (events queue). To
keep a session out of pickup, do not open it with `yk pickup session`; to stop pickup altogether, `yk pickup off`. A manual `/pickup <n>`
works in any session and does not touch the channel. Two sessions never take the same issue: the skill moves it to In progress, and the tick
skips issues with a PR or an `issue/<n>` branch.

**Safety.** The event carries only the number, title and URL; the skill reads the body with `gh` and treats it as untrusted. Only issues
that are assigned to you and Ready on the project are handed over. There is no permission relay: the session keeps its own permission
mode (`auto` recommended) and a prompt waits at the terminal. Never start it with `--dangerously-skip-permissions`.

## Prerequisites
`git`, `jq`, `curl`, `flock`, `python3`, `bun` (the channel), Java 25, Go (the CLI is built with the server). Optional: `opengrep`, `trivy`
(skipped when absent), `gh` + `cosign` for `yk up --release`, `mint` (docs links), `reuse`, `actionlint`, Ollama with the
triage model for the heal triage (without it heal starts at the average level).

## Quick start
```bash
examples/11-dogfood/yk up            # build origin/main (minutes the first time per commit) and start it
examples/11-dogfood/yk plan          # what a check of your changes would run
examples/11-dogfood/yk check         # run it; a failing stage is healed or ends the run
examples/11-dogfood/yk status        # what runs, stale or not (exit 3 when down)
```
`yk check`, `yk gradle` and `yk tool` start the server on demand. In Claude Code the SessionStart hook puts `yk` on PATH.

### Keeping the server current
`yk up` fetches `origin/main` into a managed worktree (`~/.local/state/yukta-dev/main`), builds `:boot:bootJar` + the CLI into
`builds/<commit>/` (three are kept) and runs the jar. When main moved it swaps the server **only while no execution is
running**; `yk check` and `yk gradle` complete a pending swap when idle. `yk up --release [tag]` runs a published release
instead; `yk down` stops the server yk started. The server needs an API key (it runs any process): `yk` creates one per
machine in `~/.local/state/yukta-dev/api-key` and sends it with every call.

## Decision model (optional)

Some steps can ask a small System One model for a decision (a label with a probability). It is optional and fails open: with no
model, a stale one, a slow answer or a digest over the cap, the step takes the path it took before and prints one
`WARN decision model unavailable: <why and the fix>`. Settings are in `decisions.json`; `YUKTA_DECISION_MODEL`,
`YUKTA_DECISION_URL`, `YUKTA_DECISION_MAX_INPUT` (characters, question included) override them and `YUKTA_DECISION=off` disables it.
No model is assumed: set `YUKTA_DECISION_MODEL`. `yk decision` (and `yk status`, from a 10 minute cache) probes Ollama: reachable,
version, model present and not in an old format, and the time of a small and a 1 KB digest. Scripts call `decide NAME STATE QUESTION
CRITERIA-JSON` from `scripts/_lib.sh`. A decision runs in **shadow** by default: the model is asked and `{label, probability, input hash,
latency}` goes to `.yukta-dev/decisions.tsv`, but the caller's static path still decides. `YUKTA_DECISION=shadow|on|off` sets the mode of all,
`YUKTA_DECISION_<NAME>` or `decisions.<name>.mode` of one. `decision_outcome NAME STATE LABEL` records what really happened; `yk stats
--decisions` joins the two and prints agreement, a calibration table and whether the `bar` of the decision in `decisions.json` is met
(a decision below it stays in shadow); `scripts/test-decision.sh` tests it against `fake-ollama.py`.
`yk advise` (`scripts/advise.sh`, tested by `scripts/test-advise.sh`) recommends a test tier (CLAUDE.md "Test Execution Policy") for the current changes: a deterministic floor from the paths, then one `decide` over a per-file digest (path, lines added/removed, kind; no diff) that can only widen it (`public-contract` to tier 2, `build-or-config` to 3, unsure to one more). No model, an error or an oversized digest leaves the floor. It is advice only; nothing is skipped because of it.

## Self-healing check

`yk check` runs the stages of `yk plan` one after the other (cheap first). A failing stage goes to `heal`:

1. **Free fix**: `spotlessApply`, `make fmt`, `prettier --write`, `eslint --fix`, `make mod-tidy`. No model; the stage runs again.
2. **Baseline**: the failing stage runs once on a clean checkout of `origin/main` (kept in `.yukta-dev/baseline/<sha>`). When it fails
   there with the same problem lines, the stage is a known baseline failure: the heal ends with no fixer. No `origin/main`, a
   timeout or any other error means "not known" and the heal goes on.
3. **Triage**: a local model (System One, `tev1` via Ollama) rates the failure easy, average or hard. The label only picks the
   first rung; unreachable or unsure (< 0.3) means average. `--triage off` skips it. Optional `triage.stop` in `heal.json` (off by default) adds labels such as `environment` that end the heal with no fixer (`FAILED`, reason `ESCALATE (<label>): …`); enable one only after shadow-mode calibration (#1246) shows the router agrees with what fixed the failure.
4. **Ladder** (`heal.json`): easy `haiku/low → sonnet/low`, average `haiku/high → sonnet/low`, hard `sonnet/low`. One fresh
   Claude session per rung. An attempt that changed no file ends the ladder, and so does the first failed attempt on `reuse`, `docs` or `actionlint` (`heal.json` `singleAttempt`); the end of the ladder fails the stage. An attempt after which the stage fails exactly as it did after the attempt before (same problem lines, numbers aside) ends the ladder too: a stronger model would start from the same problems.
5. **Guard**: what changed on disk decides, not what Claude says. After the rules, the `guard` decision (decisions.json, shadow by default) asks the decision model once about per-file facts, never the diff; once it is `on` it can only reject (`GUARD: model:`), at probability >= `minProbability`. Rejected: edits to `config/`, `build-logic/`, `gradle/`,
   `.github/`, `.claude/` or this folder, added suppressions, removed assertions, test expectations changed with no code
   change, a claimed fix with no change. Fixers never commit.
   **MCP for fixers**: a fixer reads more of the failed run on demand instead of being handed all of it. `claude.sh` gives it the server's `/mcp` with the read tools only (`get_node_output`, `get_execution_tree`, `get_execution_logs`, `get_execution_result`, `get_workflow_status`, `get_workflow_history`, `get_workflow_details`); the mutating tools are denied in `_claude.sh` and the key is a named `VIEWER` key (`fixer-key` in the dev state dir, added to `server.properties` when `yk up` starts the server, so a server started before this change needs a restart), which the server enforces. `YUKTA_FIXER_MCP=off` turns it off; another server needs `YUKTA_SERVER_URL` and a `VIEWER` key in `YUKTA_FIXER_MCP_KEY`. Without a key a fixer has no MCP, as before. `scripts/test-claude-mcp.sh` tests the arguments.
6. **Verify**: if anything was healed, the Gradle tasks of all stages run once more; a failure goes back to heal.

`licenses` and `security` are never healed. `--no-heal` ends the run at the first failing stage. With two or more Gradle stages planned, a `fast` step first runs all their tasks as one Gradle invocation (one start-up, one task graph); when it passes the Gradle stages are no-ops, when it fails the stages run one by one as before, so the failing stage, `failed_node` and the healing are unchanged. `--no-fast` skips it. `--models haiku,sonnet`
leaves a model out (its rungs stop at once).

Fixers run through `scripts/claude.sh` / `scripts/_claude.sh`: `--permission-mode auto --permission-prompts none` with a command
allowlist, the models pinned to `claude-haiku-5-5` and `claude-sonnet-5-5`, no user plugins, skills, MCP servers or CLAUDE.md,
a per-effort budget and time box. `YK_ADVISOR=sonnet` (experimental, off by default) gives a fixer an advisor model. `YUKTA_BUDGET_CHECK` (USD, default `checkBudget` in `heal.json`, $5) caps what all attempts of one check may cost together: once spent, no further rung starts and the stage ends as the ladder does. The prompt of an attempt carries 12-line excerpts around the `file:line` spots in the problem lines (up to 6). The free fix of `reuse` is `scripts/fix-reuse.sh` (the project header on every file `reuse lint` reports, a `.license` sidecar for types it cannot comment), so `healed=reuse:auto` costs no model call.

### Configuration
- `heal.json`: ladders per level, efforts (budget, time box), triage model and URL, the healable stages with their free fixes,
  the extra tools (ui, docs, reuse, actionlint). `YUKTA_TRIAGE_MODEL` and `YUKTA_TRIAGE_URL` override the triage model and URL.
- `fixer-rules.md`: the common system prompt of every fixer. `fixer-rules/<kind>.md` is appended for the failed stage (`style` → `pmd`, `spotbugs`, `tests` → `junit`, `coverage`, `compile`, `ui_vitest` → `vitest`, other `ui_*` → `ui`, `cli_*` → `go`); any other stage gets the common prompt alone. One constant file per kind, so the prompt cache holds. To add a role, add the file and a line in the `case` of `scripts/claude.sh`.
- `session.json` is **generated**: after editing `heal.json`, run `python3 scripts/gen-heal.py` and `python3 scripts/gen-check.py`,
  then commit it.
- Events of every heal step: `~/.local/state/yukta-dev/heal-events.jsonl`; records of one run: `<checkout>/.yukta-dev/heal/`.

## Layout
| Path | What |
|---|---|
| `yk` | the one entry point |
| `session.json` | the five workflows: `tool`, `claude`, `attempt`, `heal`, `check` (generated) |
| `scripts/plan.sh` | the changes → stages, commands and free fixes |
| `scripts/gen-heal.py`, `scripts/gen-check.py` | generate the workflows into `session.json` |
| `scripts/yukta-heal.sh` | the script steps of heal and attempt, and the guard |
| `scripts/claude.sh`, `scripts/_claude.sh` | the slim headless Claude launcher |
| `scripts/test-fixer-role.sh` | offline test that a stage gets its fixer role |
| `scripts/_lib.sh` | server, session and watch helpers |
| `scripts/test-heal.sh` | the end-to-end test of check and heal against a fake claude (`yk up` first; no API cost) |
| `scripts/test-hooks.sh` | the Claude Code hooks |
| `scripts/test-notify.sh` | offline test of `notify_session` (auth and user line to a stub socket, off switches, never fails), the message text and the `--notify` flag check |
| `channel/` | the generic Claude Code channel (Bun, TypeScript): an HTTP API to send events into a session and read Claude's replies, with permission relay; its `pickup` profile hands one GitHub issue at a time to an open session (`yk pickup session`). See `channel/README.md` |
| `scripts/pickup.sh`, `scripts/gen-pickup.py` | `tick` (hand the next assigned, Ready issue to the channel) and the generator of the `pickup` workflow in `session.json` |
| `scripts/test-pickup.sh` | offline test of `tick` (real channel, fake `gh`) and of `busy()` ignoring the schedule |
| `scripts/test-yk.sh` | interrupt, detach and `--stages` of `yk` against the dev server (no cost) |
| `eval/triage/` | the triage model evaluation |
| `backup/` | the previous implementation, kept while the loop is rebuilt |

## Notes
- Sessions: one per checkout and command (`check-<branch>-<hash of the path>`, suffix `-plan` for `yk check`, `-gradle` for `yk gradle`/`yk tool`); the library is applied to it on every run, and the working directory travels in the start payload.
- One healing `yk check` per checkout at a time (`.yukta-dev/check.lock`; the refusal names the execution that holds it). `yk gradle`, `yk tool` and `yk check --no-heal` do not edit the checkout, take no lock and queue on the server (`yk tree` shows QUEUED).
- The previous README (issue and epic runs, heal analysis, the analyzers heal) is in git history before this change.

## Gradle start-up (measured 2026-10-28, #1224)
- **One daemon.** Every `yk gradle` stage runs as a child of the dev server with the server's environment; after hours of runs next to an interactive session there was a single `GradleDaemon` process (4 h uptime, about 3.8 GB), so the stages reuse the daemon instead of starting one each. If `JAVA_HOME` or the JVM args of the server ever differ from your shell's, Gradle starts a second one: `yk gradle --status` lists them.
- **Unscoped no-op `spotlessCheck`: about 10 s** on a warm daemon (not the 4.6 s of the estimate: spotless itself walks every source set). A scoped stage (`:core:...`) stays near the 1.8 s of direct Gradle. The 2.5 s target does not hold for an unscoped run; scope it (`yk check` already does).
- **Configuration cache stays off.** `--configuration-cache` on the same task took about 40 s, four times slower, and logged `Stopping file watching and invalidating VFS after an error`: tasks of the build (the Go tasks of `cli`, per the comment in `gradle.properties`) are not compatible, so each run stores a new entry and then discards it. Fixing or excluding those tasks is a build change of its own, not part of the dev loop. The build cache (`org.gradle.caching=true`) is already on.
