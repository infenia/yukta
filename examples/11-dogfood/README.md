# 11 — Dogfood: develop Yukta with Yukta

**Use case:** run this repo's own `gradle check` as a Yukta workflow, so Claude Code (or you)
*reads one deterministic result* instead of choosing which checks to run.

```
CONSTANT_SOURCE ──► spotless ──► compile ──► tests ──► … ──► cli ──► CONSOLE_TERMINAL     (gradle-check)
CONSTANT_SOURCE ──► spotless ──► vet ──► CONSOLE_TERMINAL                                  (quick-check)
```

Each stage is a `PROCESS_EXECUTOR` running `./gradlew <tasks>`; the first failing stage fails the run.

**Trigger**
```bash
curl -X POST localhost:8080/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-11-dogfood","workflowId":"quick-check"}'
```
(`projectPath`/`workingDir` in `session.json` is `__PROJECT_PATH__`; `run.sh` fills it in.)

**Run:** `./examples/11-dogfood/run.sh` runs `quick-check`; `FULL=1 ./examples/11-dogfood/run.sh` runs
the whole `gradle-check` (minutes). It targets `YUKTA_URL`, else the `yk` dev server (`127.0.0.1:9858`) when it is up, else
`http://localhost:8080`.

**Expected:** the run finishes `SUCCESS` and the logs show `success=true` for every stage.

**Notes:** `session.json` is the single definition used by `run.sh` (REST) and by the CLI scripts below.

## Development flow: `yk` and the dev server
```
origin/main ──build──▶ dev server :9858 (one per machine)        ~/.local/state/yukta-dev
                          ▲
any checkout/branch ──yk──┘  session apply → workflow start → problems-only watch → RESULT
```
`yk` (this folder) is the one entry point. The server is always built from `origin/main`, whatever
branch you are on, and runs on **`http://127.0.0.1:9858`** (`YUKTA_DEV_PORT` overrides); the workflows run
Gradle on *your* checkout. Sessions are keyed by branch + checkout path, so several checkouts share it.

## Prerequisites
`git`, `jq`, `curl`, `flock`, Java 25, Go (the CLI is built with the server). Optional: `opengrep`,
`trivy` (skipped when absent), `gh` + `cosign` for `yk up --release`.

## Quick start
```bash
examples/11-dogfood/yk up            # build origin/main (minutes the first time per commit) and start it
examples/11-dogfood/yk check -m core # the staged, self-healing gate for one module
examples/11-dogfood/yk status        # what runs, stale or not, running executions (exit 3 when down)
```
`yk check` and `yk gradle` start the server on demand. In Claude Code the SessionStart hook runs
`yk up --background` and puts `yk` on PATH.

### Keeping the server current
`yk up` fetches `origin/main` into a managed worktree (`~/.local/state/yukta-dev/main`), builds
`:boot:bootJar` + the CLI into `builds/<commit>/` (three are kept) and runs the jar. When main moved it
swaps the server **only while no execution is running** (otherwise `STALE … swap pending`); `yk check`
and `yk gradle` complete a pending swap when idle. `yk up --release [tag]` runs a published release
instead (native executable, `SHA256SUMS` signature checked with `cosign`, `YUKTA_SKIP_VERIFY=1` skips that);
`yk down` stops the server yk started. Files: `server.log`, `server.env`, `up.log`, `build.log`, `sessions/`,
`api-key`.

The server needs an API key: it runs any process (`YUKTA_PROCESS_ALLOW_ANY`), so an open API on loopback would
let any local account run commands as you. `yk` creates one per machine in `~/.local/state/yukta-dev/api-key`
(readable by you only) and sends it with every call; from your own shell use
`curl -H "X-API-Key: $(cat ~/.local/state/yukta-dev/api-key)" …` or `YUKTA_TOKEN=$(cat …) yukta-cli …`.

### Running it from Claude Code
`.claude/settings.json` registers `.claude/hooks/yk-hook.sh` (paths resolved with `git rev-parse`, so it
works when Claude starts in a module folder):
- `SessionStart`: records the uncommitted changes the session began with, runs `yk up --background`,
  puts `yk` on PATH and tells Claude to build and test through it.
- `PreToolUse(Bash)`: denies `./gradlew`, `gradle` and `go test|vet` and names the `yk` command to use
  instead (`bootRun` stays allowed; the self-heal fixer, `YUKTA_HEAL_FIXER=1`, is exempt).
- `Stop`: when the session changed code, asks once per change set for `yk check -m <changed modules>`.
`YUKTA_DOGFOOD=off` turns all of it off.

## Running checks
```bash
yk check                                  # every stage over every module (the full gradle check)
yk check -m core -m web                   # the same stages, restricted to those modules
yk gradle :core:test --tests 'com.infenia.yukta.Foo*'   # any gradle args as one stage (failed_node=gradle)
```
- `-m <module>` (a gradle path without the leading colon, repeatable) runs the stages as
  `:<module>:<task>`; the root-only `trivy` and other modules' tasks (e.g. `:cli:check`) are dropped;
  `-m cli` runs only `:cli:check` (Go module).
- `yk gradle` args are passed verbatim, one argument each (quote filters with spaces).
- One check per checkout at a time (`.yukta-dev/check.lock`).

### Watching and controlling a run
`yk check` and `yk gradle` follow the run and print **problems only**, then the `RESULT` line. `--detach`
starts it and returns `STARTED execution=<e> session=<s>`;
`yk watch <s> <e>` follows it (`--all`: every event, for humans).
Every control-bus event has a severity, `INFO`, `WARN` or `ERROR` (`GET /api/events/stream?severity=WARN`
sends that and above): a failed or timed-out node or run is `ERROR`, so is a log line with `error`,
`exception`, `failed`, `failure` or `What went wrong`; a rejected control command, a cancelled node or run
and a `warn`/`deprecat…` line are `WARN`. A failed node's status event also carries the reason
(`metadata.error`). The problems-only output is `NODE <node> <status>` for a failed node,
`ERROR|WARN <node>: <line>` (each once, 20 per node at most), `CONTROL <command> applied=false [reason]`,
`HEAL …`, `WORKFLOW <status>`, then `SLOW <node> <s>s (median <m>s, <n> runs)` for a stage over 15s and
1.5x the median of its last 10 runs at the same scope (`.yukta-dev/durations.tsv`), then `RESULT`.

Steer a run (an execution id may be its first 8 characters):
```bash
yk tree <e>                                           # the run and the runs below it, with current nodes and pending signals
yk pause <e> [--tree]                                 # or resume / stop; --tree: and every run it started
yk retry <e> <node>                                   # re-run a failed node and what is behind it
yk skip <e> <node>                                    # or unskip
yk signals [<e>]                                      # what waits for an answer
yk approve <e> [signal]                               # or reject
yk break <e> <node> --field payload.status --equals error   # --clear removes it
yk step <e> <node> on                                 # or off / next
```
`scripts/test-steer.sh` runs these against the dev server with a throwaway project.

### Inside Claude Code
`.claude/settings.json` wires it up (hooks run from `.claude/hooks/yk-hook.sh`, found through
`git rev-parse --show-toplevel` so they also work when Claude starts in a module folder):
- **SessionStart** starts the server in the background and tells Claude to build and test through `yk`;
  **UserPromptSubmit** adds one line naming the yukta skill when a prompt is about building, testing or checking;
  **PreToolUse** denies direct `./gradlew`, `gradle` and `go test` with the `yk` command to use instead;
  **PostToolUse** records the Gradle module of every file the session edits; **Stop** asks once for `yk check -m <those
  modules>` unless a check already passed on exactly the current changes (`.yukta-dev/last-result`).
- The **status line** (`yk statusline`, cached, no network) shows `yukta :9858 main@ab12cd3 ✓ | 2 running · 1 awaiting | last: core SUCCESS (healed style)`.
- **Slash commands** `/yk-check`, `/yk-up`, `/yk-finding`; the **MCP** server `yukta` (`.mcp.json`, key from `yk mcp-headers`)
  with the read-only tools pre-approved; `YUKTA_DOGFOOD=off` turns the hooks off.

### Dogfood backlog
Findings are GitHub issues labelled `dogfood`, filed with `yk finding` (`scripts/report-finding.sh`, or
the "Dogfood finding" issue form) and triaged onto the Yukta Roadmap project:
```bash
yk finding --title "<one-line title>" --module <module> --priority P2 --kind bug \
  --workflow <wf> --execution <e> --evidence '<the log / CONTROL / SLOW / RESULT line>' --repro '<command>'
```
The same title again adds a comment to the open issue instead of filing a duplicate. `--dry-run` prints
the issue without filing it.

### Result
Exit 0 only when the run ends `SUCCESS`; the last line is
```
RESULT status=FAILURE failed_node=style healed=spotless:auto execution=<id> session=check-<branch>-<hash>
```

## Self-healing check
`yk check` turns the `gradle-check` stage table into ONE workflow, `check`, so a run is one execution that
`yk watch` follows and `yk retry` can steer. `heal_workflow` (`scripts/_lib.sh`) builds it from `session.json`
and the policy `heal.json` (so `-m` works). The success path is the stage chain; a failing stage hands over
to another workflow, `heal`, which `check` calls as a `SUB_WORKFLOW` and waits for:
```
check   S.run1 ─failure▶ [S.f1 auto ▶ S.run2 ─failure▶] S.heal (heal) ─ok▶ next stage
           └─success▶ next stage                                  └─fails▶ the run fails (failed_node=S)

heal    brief ▶ triage (System One CLASSIFY) ─ difficulty ▶ fix-<model>-<effort> ─failure▶ fix-<next model>-low ▶ … ▶ giveup
                                                                     └─success▶ done
fix     fix@low ▶ recheck ─failure▶ fix@medium ▶ recheck ─failure▶ fix@high ▶ recheck ─failure▶ the fix fails
           (fix-from-<effort> starts at that effort; model, stage and effort come from the start payload)
```
- **triage** is a `CLASSIFY` on the System One backend (`heal.json` `triage.model`, `kev:4b-q4_K_M`; it needs Ollama 0.35 or newer with that decision model, and any failure of the call starts at the default level). `minProbability` is 0.3, not the usual 0.6: the probability is spread over seven ordered levels (a clear answer is 35 to 55 %), and a vague one falls back to the default level, which is the right guess for it: `brief` (the stage, its
  command, the problem lines, the failing tests, then the files changed with their added and removed lines and the end of the log, 5000 characters in all) goes in, a difficulty comes out and `triage.levels` maps it to a
  start rung: `trivial` haiku/low, `easy` haiku/medium, `simple` haiku/high, `moderate` sonnet/low, `average`
  sonnet/medium (the default, also for an uncertain answer or a model that is down), `hard` sonnet/high,
  `veryhard` opus/low. It only picks where to start: a failure always goes on to the next model.
  The question and the level descriptions in `heal.json` are the prompt and matter most: on 14 held-out hand-labelled failures, longer descriptions with concrete signals took the exact match from 4 to 8 and, with the longer question and the log, to 10 (13 within one level).
- **fix** runs one model at rising effort. Every attempt is time-boxed and budgeted (`heal.json` `efforts`:
  `timeoutSeconds`, `budget`), and `recheck` re-runs the failing stage's own command (`yukta-heal.sh run`
  records it) after each one, so the attempt is judged by the check it was meant to fix.
- **heal** escalates the model: a model that fails at every effort hands over to the next one at `low` again.
  When the last model has failed, `giveup` fails `heal` and so the run.
- Depth is check → heal → fix (2; the engine allows 5). `heal.json` `models` is the order of the models.

`yk check --models sonnet,opus` limits the models for one run, `--triage off` skips the System One call (heal
then starts at the default level; the online test has no Ollama), `YUKTA_TRIAGE=off` is the same.

The deterministic fix (`auto`, only `spotless`: `spotlessApply`) still comes first. A healed stage re-runs only itself. After `coverage` the
**analyzers** (style, spotbugs, licenses, security, cli) run in parallel; those with a policy go through
`analyzers.join` (an `AGGREGATE`), `analyzers.gate` (a `BRANCH` on a failed item) and ONE `analyzers.heal`, so two
fixers never edit the tree at once (`recheck` of the analyzers is one gradle rerun of the failing ones). `licenses` and
`security` have no policy: they fail the run at once. `verify` re-runs every task if anything was healed
(`yukta-heal.sh verify`), then `done`.

**Extra stages.** `ui` (`:ui:pnpmLint :ui:pnpmTest`, the Gradle toolchain's pnpm), `docs` (`mint broken-links`), `reuse` (`reuse lint`) and
`actionlint` (local binary, else the pinned docker image of `ci.yml`) are analyzers too, from `extra` in
`heal.json`. A stage whose tool is not installed is skipped with a `WARN`; scoped to `-m <mod>` only `ui` and
`docs` apply to their own module. Only the `actionlint` fixer may edit `.github/workflows/*.yml` (alone, never
when another analyzer fails with it). `YUKTA_CHECK_EXTRAS=off` leaves them out.

**Checkpoint.** `yk check --pause before-opus` puts a `WAIT_FOR_SIGNAL` (`before-opus`) in front of every opus
fixer (`pause-opus` in `heal`): whoever steers the run can approve it (`yk cli workflow node signal <e> <node> --name before-opus
--decision approve`), reject it (the run ends), or take over; unanswered it lets the run go on after
`YUKTA_PAUSE_SECONDS` (600). It is off by default until the steering surface (PR4) shows pending approvals.

**Parallel gradle.** Every gradle node is in the server's `gradle` concurrency group
(`yukta.concurrency.groups.gradle`, 2 by default, `YUKTA_DEV_GRADLE_PARALLEL` at `yk up`): the analyzers wait
`QUEUED` instead of each starting a 4 GB daemon.

| stage | `auto` | heal | in `heal.json` |
|---|---|---|---|
| spotless | `spotlessApply` | yes | |
| compile, tests, coverage, style, spotbugs, cli | | yes | |
| licenses, security | | | no entry: plain fail-fast |

`session.json` stays the linear spec (and the `--no-heal` workflow). `test-self-heal.sh` checks the generated
graph offline; `test-self-heal.sh --online` also runs it on the dev server against a throwaway project with a
fake `gradlew` and a fake `claude` (`YUKTA_CLAUDE_BIN`).

Every node is `yukta-heal.sh <action>`: `run <stage> -- <cmd>` keeps the stage's output and exit code (the
fixers read the problem lines from there, nothing is run twice), `fix <stage> <n> <mode>` is one repair
attempt, `rerun analyzers` one gradle run of the analyzers still failing, `verify`. A Claude attempt builds a
short brief (the problem lines only, the failing test classes, the working-tree diffstat, the previous
attempt's summary) and runs `claude -p` through one slim launcher (`yk_claude`): none of your plugins, skills,
settings or MCP servers, a fixed tool list, `acceptEdits`, no `git commit`/`push`, the constant system prompt
`fixer-rules.md` (everything that varies is in the user prompt, so every attempt shares one cached prefix),
budget `YUKTA_HEAL_BUDGET` = $1. It then runs `spotlessApply` and judges the attempt by what changed on disk:
touching `config/`, `build-logic/`, `gradle/`, `.github/`, `.claude/` or this folder, adding a suppression
(`@SuppressFBWarnings`, `NOPMD`, `CHECKSTYLE:OFF`, `@SuppressWarnings("PMD")`, `@Disabled`), removing assertions,
or changing nothing marks it `fixed=false` (`summary=GUARD: …`). It never reverts (the tree may hold your own
edits) and never commits.
- It prints `HEAL <stage> attempt=<n> mode=<m> fixed=<bool> files=<n> cost=$<x> summary=<text>` and writes
  `.yukta-dev/heal/<session>/<stage>-<n>.json` (with the token usage); `RESULT` gains `healed=<stage:step,…>`
  (step = `auto` or the model). `--no-heal` turns healing off.
- The server needs `YUKTA_PROCESS_ALLOW_ANY=true` (`yk up` sets it) and a logged-in `claude` on its PATH
  (not `--bare`, which loses the login).

## Issues: `yk run issue`
```
yk run issue 123 [--detach] [--no-pause before-implement,before-pr]
```
One command takes an assigned, **Ready** issue of the Yukta Roadmap project to a draft pull request. The shared session
`yukta-dev` (applied by `yk`, parameterised by the start payload `issue`, `repo`, `worktree`) holds:
- `run-issue`: runs `implement-issue` as a sub-workflow. However that ends badly (a failing node, a rejected or
  unanswered checkpoint, a failing check) `release` puts the issue back to Ready and the run ends `ESCALATE`
  (`RESULT status=ESCALATE`); the worktree is kept.
- `implement-issue`: `claim` (open, assigned to you, Ready; moves it to In progress) → `worktree` (`../yukta-issue-<n>` on
  `issue/<n>` from `origin/main`) → `brief` (`.yukta-dev/issue-<n>/brief.md`) → **before-implement** → `nav` (a code-navigation index of the worktree, below) → `implement` (a headless
  Claude, `YUKTA_IMPL_MODEL` opus, `YUKTA_IMPL_BUDGET` $5, in the worktree with the implementer rules in `implementer-rules.md`)
  → `check` → **before-pr** → `pr` (commit, push, a *draft* PR with `Fixes #<n>` and the check's `RESULT` line).
- `check`, `heal`, `fix-from-<effort>`: the self-healing check with the project given as `${start.project}`, so it runs on any
  worktree (see "Self-healing check"). In an issue run it also gets `issueState` (`.yukta-dev/issue-<n>/`): the implementer's cost
  counts against `YUKTA_BUDGET_ISSUE`, and `nav.json` there gives the fixers the same code index as the implementer. The fixers are
  **fresh sessions** chosen by the triage; they do not resume the implementer.

The two checkpoints are `WAIT_FOR_SIGNAL` nodes: answer with `yk signals <e>` / `yk approve|reject <e> <signal>` (the root
execution id is enough, the signal waits in a run below it). `before-implement` is the time to edit the brief; an
unanswered `before-implement` goes on after an hour, an unanswered `before-pr` fails the run (nothing outward-facing
happens by itself). `scripts/yukta-issue.sh` holds the steps (`YUKTA_GH` swaps `gh`, `YUKTA_PROJECT_STATUS_CMD` the project
update, for tests); `scripts/test-issue.sh` runs them on the dev server with a fake repository, gh and claude.

### The issue run's steps: `nav`, `implement`, `check`

A slim implementer with a **code-navigation index**, so it and its fixers can search the code instead of reading files one by one (the sub-flows otherwise have six built-in tools and no index; see issue #1251), then the self-healing `check`:

```
claim -> worktree -> brief -> [before-implement] -> nav -> implement -> check (heal) -> [before-pr] -> pr -> done      (run-issue)
```


`nav` (`scripts/nav.sh prepare`) indexes the issue worktree with the first backend that works, in the order of `YUKTA_NAV_BACKENDS` (default `ide,cbm,codegraph`), and writes `.yukta-dev/issue-<n>/nav.json`. It never fails the run: with no working backend the run goes on without an index (`backend: none`, which the analyzer notes).

| Backend | What it is | Cost to index (measured on this repository) | Notes |
|---|---|---|---|
| `ide` | the IDE's index MCP server (`intellij-index`, default `http://127.0.0.1:29170/index-mcp/streamable-http`, `YUKTA_NAV_IDE_URL`) | the IDE's own indexing | **Opt-in: `YUKTA_NAV_IDE=open`.** Asking for the worktree makes the IDE open it as a project (a new window and a fresh index per issue). Only a read-only subset of its tools is allowed (no `refactor_*` or `move_file`). Tested against a stub of the server; not run against a real IDE by the loop's tests. |
| `cbm` | [codebase-memory-mcp](https://github.com/DeusData/codebase-memory-mcp), the binary on your `PATH` (`YUKTA_NAV_CBM_BIN`) | about 13 s, 23k nodes | **Not pinned**: its version is recorded in `nav.json` and on each outcome. Its index is under `YUKTA_NAV_CBM_CACHE` (default `$DEV_HOME/cbm`, about 120 MB per worktree; safe to delete). Only its query tools are allowed. Finds Java and Go symbols. |
| `codegraph` | CodeGraph, `codegraph init` in the worktree, used through Bash | about 8.5 minutes | The last resort; the time is wall time, not tokens. |

- **Your Claude configuration is not touched.** A backend that needs an MCP server is handed to the headless sessions with `--mcp-config` for that run only (`nav-mcp.json`); the sessions keep `--strict-mcp-config`, so nothing else loads.
- **One tool set for the whole issue.** `yk_claude` reads `nav.json` (next to `implement.json`) for the implementer *and* for every fixer, so they all see the same index. The rules are one constant file per backend (the implementer's rules plus `nav/rules-<backend>.md`); what differs per issue (the project name or path) goes in the prompt.
- **Every run indexes again:** the `nav` step rewrites `nav.json` (an earlier run's index is never reused).
- `nav.json` holds `{backend, ok, index_seconds, version}`; the index time is part of the run's wall time.
- Settings: `YUKTA_NAV_BACKENDS`, `YUKTA_NAV_IDE`, `YUKTA_NAV_IDE_URL`, `YUKTA_NAV_CBM_BIN`, `YUKTA_NAV_CBM_CACHE`, `YUKTA_NAV_INDEX_TIMEOUT` (1500 s, per backend). `scripts/nav.sh probe` shows what could run now and why a backend is skipped.
- Offline test: `scripts/test-nav.sh` (fake backends and a stub IDE server).

## Epics: `yk run epic`
```
yk run epic 12 [--detach] [--no-pause before-implement,before-pr,between-issues]
```
`run-epic` takes the open sub-issues of epic 12 that are assigned to you and Ready (`yukta-issue.sh epic-list`, which
prints the list as JSON for a `SPLITTER`) and runs `run-issue` for each, **two at a time** (`SUB_WORKFLOW` with
`concurrency: 2`; each issue has its own worktree, so their checks do not collide, and the gradle concurrency group keeps
the Gradle builds to two). The implementers start `YUKTA_WARM_DELAY` seconds apart (20), so the second one finds the first
one's prompt prefix cached. An issue that is not Ready is left out; every issue still ends with its own draft PR, or Ready
again with its worktree kept.
- **between-issues** is a checkpoint in front of each issue: an issue starts when you approve it (`yk approve <e> between-issues`,
  once for each). `--no-pause between-issues` starts them as slots free.
- **Escalation.** Two issue runs that end `ESCALATE` pause the epic and every run below it (`epic-pause` calls `yk pause --tree`
  through the run's own `YUKTA_EXECUTION_ID`) and print a warning with the commands to go on (`yk resume <e> --tree`) or give up
  (`yk stop <e> --tree`). The epic ends `RESULT status=ESCALATE` when any issue did not finish.
- **Budgets.** `YUKTA_BUDGET_ISSUE` (USD) caps what one issue spends, the implementer and its fixers together; `YUKTA_BUDGET_EPIC` what
  all issues do. Over a budget `implement` refuses to start and a fixer refuses to spend, so the run ends instead of paying more;
  set them in the environment of `yk run`. The implementer's own cap is `YUKTA_IMPL_BUDGET` (5).
- **`yk heal report`** shows where the heal time and money went (see "Monitoring heal").

`yk watch` prints `APPROVAL <signal> waits at <node>: yk approve <e> <signal>` once for each checkpoint that starts waiting, so a watcher
(the Monitor tool, the `yukta-run-watcher` subagent) sees what needs an answer. `scripts/test-epic.sh` runs the epic on the dev server with a
fake repository.

## The check workflow
`session.json` is `CONSTANT_SOURCE` → one `PROCESS_EXECUTOR` per stage → `CONSOLE_TERMINAL`.
Stages run sequentially and fail fast (avoids concurrent Gradle runs fighting over one build dir):

| node | gradle tasks | reports |
|---|---|---|
| `spotless` | `spotlessCheck` | console |
| `compile` | `compileJava compileTestJava` | console |
| `tests` | `test jacocoTestReport` | `<module>/build/test-results`, `build/reports/tests` |
| `coverage` | `jacocoTestCoverageVerification` | `<module>/build/reports/jacoco` |
| `style` | `checkstyle{Main,Test} pmd{Main,Test}` | `<module>/build/reports/{checkstyle,pmd}` |
| `spotbugs` | `spotbugs{Main,Test}` | `<module>/build/reports/spotbugs` |
| `licenses` | `licensee` | console |
| `security` | `opengrep trivy` | `<module>/build/reports/{opengrep,trivy}` |
| `cli` | `:cli:check` | console |

`failed_node` tells you which stage broke; open only that stage's report. With `-m <module>`, the
`<module>` in the report paths is that module.

## Triage evaluation: `eval/triage/`

`eval/triage/` scores the triage prompt of `heal.json` against real failures of the repository's CI, so a change to the
question, the level descriptions or the brief is judged by numbers ([eval/triage/README.md](eval/triage/README.md)).

## Using the CLI directly
```bash
yk cli session list                               # the CLI built with the server, pointed at it
yk cli -o json workflow status <session> <execution>
yk cli plugin list
```
The server also exposes the web UI (<http://127.0.0.1:9858/>) and MCP (`/mcp`) on the same port.

## Notes
- Machine state: `~/.local/state/yukta-dev/` (`YUKTA_DEV_HOME`); per checkout: `.yukta-dev/` (git-ignored).
- Sessions are stored under the server's `sessions/`; `yk` re-applies the session on each run.
- Edit stages in `session.json`; `__PROJECT_PATH__` is substituted at run time.
