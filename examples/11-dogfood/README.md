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
and the policy `heal.json` (so `-m` works). A stage is a chain; the attempt and the model of every
fixer are fixed in the graph, nothing counts at run time:
```
S.run1 ──failure▶ S.f1 ──success▶ S.run2 ──failure▶ S.f2 ──success▶ S.run3 … S.run<k+1>   (ERROR: the run fails)
   └─success▶ next stage            └─failure▶ S.f2 (the next rung)
```
The rungs are the deterministic fix (`auto`, only `spotless`: `spotlessApply`) and then the **ladder**
`haiku → sonnet → opus` (`heal.json`; `yk check --ladder sonnet,opus` for one run). A healed stage re-runs
only itself. After `coverage` the **analyzers** (style, spotbugs, licenses, security, cli) run in parallel;
those with a policy go through `analyzers.join` (an `AGGREGATE`), `analyzers.gate` (a `BRANCH` on a failed
item) and ONE shared fixer chain `analyzers.f<i>` then ONE gradle rerun `analyzers.r<i>`, so two fixers never
edit the tree at once. `licenses` and `security` have no policy: they fail the run at once. `verify` re-runs
every task if anything was healed (`yukta-heal.sh verify`), then `done`.

**Extra stages.** `ui` (`:ui:pnpmLint :ui:pnpmTest`, the Gradle toolchain's pnpm), `docs` (`mint broken-links`), `reuse` (`reuse lint`) and
`actionlint` (local binary, else the pinned docker image of `ci.yml`) are analyzers too, from `extra` in
`heal.json`. A stage whose tool is not installed is skipped with a `WARN`; scoped to `-m <mod>` only `ui` and
`docs` apply to their own module. Only the `actionlint` fixer may edit `.github/workflows/*.yml` (alone, never
when another analyzer fails with it). `YUKTA_CHECK_EXTRAS=off` leaves them out.

**Checkpoint.** `yk check --pause before-opus` puts a `WAIT_FOR_SIGNAL` (`before-opus`) in front of every opus
fixer: whoever steers the run can approve it (`yk cli workflow node signal <e> <node> --name before-opus
--decision approve`), reject it (the run ends), or take over; unanswered it lets the run go on after
`YUKTA_PAUSE_SECONDS` (600). It is off by default until the steering surface (PR4) shows pending approvals.

**Parallel gradle.** Every gradle node is in the server's `gradle` concurrency group
(`yukta.concurrency.groups.gradle`, 2 by default, `YUKTA_DEV_GRADLE_PARALLEL` at `yk up`): the analyzers wait
`QUEUED` instead of each starting a 4 GB daemon.

| stage | `auto` | ladder | in `heal.json` |
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
`yukta-dev` (applied by `yk`, parameterised by the start payload `issue`, `repo`, `worktree`, `implSession`) holds:
- `run-issue`: runs `implement-issue` as a sub-workflow. However that ends badly (a failing node, a rejected or
  unanswered checkpoint, a failing check) `release` puts the issue back to Ready and the run ends `ESCALATE`
  (`RESULT status=ESCALATE`); the worktree is kept.
- `implement-issue`: `claim` (open, assigned to you, Ready; moves it to In progress) → `worktree` (`../yukta-issue-<n>` on
  `issue/<n>` from `origin/main`) → `brief` (`.yukta-dev/issue-<n>/brief.md`) → **before-implement** → `implement` (a headless
  Claude, `YUKTA_IMPL_MODEL` opus, `YUKTA_IMPL_BUDGET` $5, in the worktree with the implementer rules in `implementer-rules.md`)
  → `check` → **before-pr** → `pr` (commit, push, a *draft* PR with `Fixes #<n>` and the check's `RESULT` line).
- `check` and `check-impl`: the self-healing check with the project given as `${start.project}`, so it runs on any
  worktree. In `check-impl` the ladder is `resume`, `resume`: a fixer **resumes the implementer's own session** (same model, warm
  cache, it knows the change) instead of starting a fresh one.

The two checkpoints are `WAIT_FOR_SIGNAL` nodes: answer with `yk signals <e>` / `yk approve|reject <e> <signal>` (the root
execution id is enough, the signal waits in a run below it). `before-implement` is the time to edit the brief; an
unanswered `before-implement` goes on after an hour, an unanswered `before-pr` fails the run (nothing outward-facing
happens by itself). `scripts/yukta-issue.sh` holds the steps (`YUKTA_GH` swaps `gh`, `YUKTA_PROJECT_STATUS_CMD` the project
update, for tests); `scripts/test-issue.sh` runs them on the dev server with a fake repository, gh and claude.

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
- **`yk stats`** adds up what the loop spent from its records: per step (`compile haiku`, `tests resume`, `implement`, ...) the attempts,
  how many fixed, the dollars and how much of the prompt was read from the cache (the target is 70 % and more).

`yk watch` prints `APPROVAL <signal> waits at <node>: yk approve <e> <signal>` once for each checkpoint that starts waiting, so a watcher
(the Monitor tool, the `yukta-run-watcher` subagent) sees what needs an answer. `scripts/test-epic.sh` runs the epic on the dev server with a
fake repository.

## Improvement loop: `yk next`, `yk pick`, `yk loop`

Runs your assigned, Ready issues one at a time, **one method per issue drawn at random**, and records what each run cost so the loop can be improved from data (plan: `design/plans/dogfood-improvement-loop.md`; the token evaluation it builds on is `eval/`, PR #1252).

```bash
yk next                          # your assigned, Ready issues by priority, with their size (S, M, L)
yk pick 1234 --dry-run           # size, loop-changing flag and the drawn method; nothing is written
yk pick 1234                     # draw (written to the ledger first), run the method, record the outcome
yk loop total                    # dollars spent of the budget ($60 by default)
yk loop ledger                   # one line per draw and outcome
```

- **Methods.** **A**: one Claude Code session fixes the issue itself and Yukta only checks. **B**: a Claude Code session orchestrates `yk run issue`; slim sub-flow sessions do the work. **C**: B with a code-navigation backend. `YUKTA_LOOP_METHODS` lists the enabled ones (default `B`; A and C are enabled as they land). With only B enabled the draw is not random yet.
- **Draw.** Block-randomized inside each size stratum: a block holds every enabled method once in a seeded order, so the methods stay balanced even with few issues. The draw is appended to the ledger **before** the run starts, so it cannot depend on how the run goes. A second `yk pick` of the same issue keeps its method. `--method X` forces one; it is recorded as forced and left out of the method comparison.
- **Size** (`scripts/issue-size.sh`): `2 x acceptance criteria + 3 x modules named + words / 150`, bucketed S below 6, M below 9, L from 9 (set on the open issues of epic #1218). A label `size:S|M|L` overrides it.
- **Loop-changing issues** (their text names `examples/11-dogfood`, `yk`, `build-logic`, the heal or issue scripts, `controlbus`; or the label `loop-changing`, overridden by `loop-neutral`) are flagged, because the ground moves under them and they should stay out of a method comparison.
- **Ledger** `$DEV_HOME/loop/ledger.jsonl` (per machine): a `draw` record (issue, size, method, seed, block, loop commit, base commit) and an `outcome` record (result, PR, wall time, cost, and tokens of the main session and the sub-flows). Token fields use the OpenTelemetry GenAI names (`gen_ai.usage.input_tokens`, `output_tokens`, `cache_creation.input_tokens`, `cache_read.input_tokens`). The loop commit is `-dirty` when `examples/11-dogfood` has uncommitted changes.
- **Budget.** `YUKTA_LOOP_BUDGET_SESSION` ($5) caps each Claude session; `YUKTA_LOOP_BUDGET_TOTAL` ($60) stops `yk pick` until you raise it, which is the review point. Costs of the sub-flows come from the heal and implement records, which a restarted run overwrites (issue #1250), so a restart can undercount.
- **Before the draw** the issue must be open, assigned to you and Ready; an ineligible issue draws nothing.
- Offline test: `scripts/test-loop.sh`.

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

## Measuring token cost: `eval/`

`eval/` compares two ways of using Yukta from Claude Code on a real issue (Claude fixing it alone, with Yukta only checking, against Claude orchestrating `yk run issue`) and reports tokens and dollars per run, the cost split between the main session and the sub-flows, and a per-turn prompt-cache trace. It runs in a scratch clone with stubbed `gh`, so no issue or PR is touched. How to run and read an evaluation: [EVALUATION.md](EVALUATION.md); harness reference: [eval/README.md](eval/README.md); the first evaluation (issue #1207) is archived in `eval/results/`.

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
