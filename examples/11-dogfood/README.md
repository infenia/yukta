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
the whole `gradle-check` (minutes). It targets `YUKTA_URL`, else `YUKTA_SERVER_URL`, else the server started by
`yukta-server.sh`, else the server already running on `http://localhost:8080`.

**Expected:** the run finishes `SUCCESS` and the logs show `success=true` for every stage.

**Notes:** `session.json` is the single definition used by `run.sh` (REST) and by the CLI scripts below.

## Development flow with a local server + CLI
```
server provider ───▶ Yukta server (own port, own state)       worktree: ../yukta-dev-<name> from main, bootRun
                          ▲                                    native:   the GraalVM executable
feature branch ──CLI──────┘   yukta-cli session apply → workflow start → logs/events → RESULT line
```
The scripts talk to the server only through `.yukta-dev/current.env` and the `yukta` CLI, so the provider is
interchangeable. `yukta-server.sh` is the single entry point (see "Server providers").

## Prerequisites
`git`, `jq`, `curl`, Java 25, Go (for the CLI). Optional: `opengrep`, `trivy` (skipped when absent).

## Quick start
**Use the server that is already running** (the default; nothing to start). The scripts pick the server from
`YUKTA_SERVER_URL`, else `.yukta-dev/current.env`, else `http://localhost:8080`:
```bash
./gradlew :cli:goBuild                                        # once, and after CLI changes (a stale binary lacks `session apply`)
curl -fsS localhost:8080/actuator/health                      # or: export YUKTA_SERVER_URL=http://host:port
examples/11-dogfood/scripts/yukta-check.sh -m core            # no server start/stop
```
Caveat: a **native** server currently cannot stream control-bus events (`--watch`/`yukta-watch.sh` fail with
`unexpected EOF`, see the `dogfood` issue with legacy id DF-5); the default log mode of `yukta-check.sh` works.

Only when no server is running, start a dedicated one (it gets its own port and state):
```bash

examples/11-dogfood/scripts/yukta-server.sh start dev            # a server on a free port (the latest release by default)
examples/11-dogfood/scripts/yukta-check.sh                       # run the checks (see "Running checks" below)
examples/11-dogfood/scripts/yukta-server.sh stop dev             # stop it (worktree: also removes the worktree; refuses if it has unmerged work, --force overrides)
```

### Server providers
`yukta-server.sh start|stop <name>` picks how the server is launched; everything else is unchanged.

| provider | selected by | how | start time |
|---|---|---|---|
| `release` (default) | nothing; `--version <tag>` or `YUKTA_SERVER_VERSION` pins a release | `yukta-release.sh`: the latest GitHub release (pre-releases included), `gh release download`, `SHA256SUMS` verified with `cosign` (signed by the release workflow, or by the maintainer for alpha.2; `YUKTA_SKIP_VERIFY=1` skips it) and the archive against it, cached in `.yukta-dev/releases/<tag>`, run as native | seconds |
| `native` | `YUKTA_SERVER_BIN=<path>` or `YUKTA_SERVER_MODE=native` | `yukta-native.sh`: a local executable, default `boot/build/native/nativeCompile/yukta` (`./gradlew :boot:nativeCompile`) | seconds |
| `jar` | `YUKTA_SERVER_MODE=jar` (`YUKTA_SERVER_JAR` overrides the newest `boot/build/libs/*.jar`) | `java -jar` of `./gradlew :boot:bootJar`; no Truffle native step | seconds |
| `docker` | `YUKTA_SERVER_MODE=docker` (`YUKTA_SERVER_IMAGE` picks the image) | `yukta-docker.sh`: a local image from `release/build-image.sh`. The image has no JDK, so it serves the Quickstart/smoke path, not the gradle stages | seconds |
| `worktree` | `YUKTA_SERVER_MODE=worktree` | `yukta-worktree.sh`: a worktree of `origin/main` run with `bootRun` (can fail on the Truffle init NPE) | minutes |

The ready line says what runs, e.g. `ready: export YUKTA_SERVER_URL=http://localhost:9101 (release v0.1.0-alpha.2)`
or `(local jar)`. `stop` uses the provider recorded when the server was started; a release server is a native one.
To add a provider, add a `yukta-<mode>.sh` that uses the helpers in `_lib.sh` and write `.yukta-dev/<name>.env`.

### Running it during development
`.claude/settings.json` registers a `SessionStart` hook (`dogfood-baseline.sh`, which records the uncommitted code
changes present when the session begins) and a `Stop` hook (`.claude/hooks/dogfood-nudge.sh`): when Claude is about
to finish and the session itself changed uncommitted code (changes that were already there do not count) it is asked,
once per change set, to run `yukta-check.sh --watch` and to file any
Yukta defect with `report-finding.sh` (the `dogfood` label). Set `YUKTA_DOGFOOD=off` to opt out.

## Running checks
`yukta-check.sh [-p project-path] [-m module] [gradle args...]` needs a running server (an existing one via
`YUKTA_SERVER_URL`/`localhost:8080`, or one started by `yukta-server.sh`). It checks the current checkout, or the one given with `-p`.

### Entire project
```bash
examples/11-dogfood/scripts/yukta-check.sh                 # every stage over every module (the full gradle check)
examples/11-dogfood/scripts/yukta-check.sh build           # or any whole-project gradle task
```

### One module
Use the gradle path of the module without the leading colon (`core`, `plugins:processors:decision`).
```bash
examples/11-dogfood/scripts/yukta-check.sh -m core         # the same stages, restricted to :core
examples/11-dogfood/scripts/yukta-check.sh :core:test --tests 'com.infenia.yukta.Foo*'   # one task, e.g. a single test class
```
- `-m <module>` runs the stages below as `:<module>:<task>` and reports `failed_node` per stage. The
  root-only `trivy` and other modules' tasks (e.g. `:cli:check`) are dropped; `-m cli` runs only
  `:cli:check` (Go module).
- Gradle args (anything that is not `-p`/`-m`) run as a single `gradle-run` stage, so a failure
  reports `failed_node=gradle`. Args are passed verbatim, one argument each (quote filters with spaces).
- `-m` and gradle args cannot be combined.

### Watching and controlling a run
`--detach` starts the run and returns at once; `yukta-watch.sh` follows it with control-bus events
(`yukta-cli controlbus watch`) until it ends. `--watch` does both in one command and shows **problems only**
(`--quiet`), which is the cheap way to follow a run (for an agent, in tokens):
```bash
examples/11-dogfood/scripts/yukta-check.sh --detach -m core      # STARTED execution=<e> session=<s>
examples/11-dogfood/scripts/yukta-watch.sh <s> <e> --quiet       # problems, then SLOW lines and RESULT
examples/11-dogfood/scripts/yukta-watch.sh <s> <e>               # every event (humans)
examples/11-dogfood/scripts/yukta-check.sh --watch -m core       # start and follow, problems only
```
Every control-bus event has a severity, `INFO`, `WARN` or `ERROR` (`GET /api/events/stream?severity=WARN`
sends that and above): a failed or timed-out node or run is `ERROR`, so is a log line with `error`,
`exception`, `failed`, `failure` or `What went wrong`; a rejected control command, a cancelled node or run
and a `warn`/`deprecat…` line are `WARN`. A failed node's status event also carries the reason
(`metadata.error`: the process's exit code, output and stderr, cut to the first and last 4000 characters).
With `--quiet` the output is:
`NODE <node> <status>` for a failed (non-SUCCESS) node, `ERROR|WARN <node>: <line>` (the reason's and the output's
problem lines, each once, 20 per node at most), `CONTROL <command> applied=false [reason]`, `WORKFLOW <status>`.
Without it, one line per event: `NODE <node> <status>`, `WORKFLOW <status>`,
`CONTROL <command> applied=<bool> [reason]`, `LOG <node>: <line>` (`--no-logs` leaves these out).
`SLOW <node> <s>s (median <m>s, <n> runs)` is printed when a stage took over 15s and 1.5x the median of its
last 10 runs at the same scope; durations are kept in `.yukta-dev/durations.tsv`.

Steer a run with the CLI, through `yukta-cli.sh` (it picks the server and the binary):
```bash
examples/11-dogfood/scripts/yukta-cli.sh workflow node retry <e> <node>        # new execution from a failed stage
examples/11-dogfood/scripts/yukta-cli.sh workflow pause <s> <e>                # or resume, executions stop <e>
examples/11-dogfood/scripts/yukta-cli.sh workflow node signal <e> <node> --name approval --decision approve
examples/11-dogfood/scripts/yukta-cli.sh workflow node breakpoint <e> <node> --field payload.status --equals error
```
This is how Claude Code works the loop (see `.claude/skills/yukta/SKILL.md`): it watches under its Monitor
tool, fixes a failure caused by its own change and retries that stage, and records any Yukta defect it
notices instead of ignoring it.

### Dogfood backlog
Findings are GitHub issues labelled `dogfood`, filed with `scripts/report-finding.sh` (or the "Dogfood
finding" issue form) and triaged onto the Yukta Roadmap project, to be fixed now or later:
```bash
scripts/report-finding.sh --title "<one-line title>" --module <module> --priority P2 --kind bug \
  --workflow <wf> --execution <e> --evidence '<the log / CONTROL / SLOW / RESULT line>' --repro '<command>'
```
The same title again adds a comment to the open issue instead of filing a duplicate. `--dry-run` prints
the issue without filing it.

### Result
The script exits 0 only when the execution ends `SUCCESS` (or `COMPLETED`); its last line is:

```
RESULT status=FAILED failed_node=style execution=<id> session=check-<branch>
```

## Self-healing check
`yukta-check.sh` runs the staged `gradle-check` as one small workflow per stage (`stage-<stage>`, built by
`stage_workflows` in `scripts/_lib.sh` from the `gradle-check` stage table, so `-m <module>` works). Each stage
has several explicit exits, named by an `OUTCOME_TERMINAL` whose outcome the supervisor and the driver act on:
```
run ──success──────────────▶ OUT SUCCESS               the driver starts the next stage
 └failure▶ apply ─success▶ OUT RETRY (restartFrom run)  the server's rule restarts the run from `run`
              └failure───▶ OUT ESCALATE                 the driver calls the Claude fixer, then restarts the stage
```
Stages with only a Claude fix go from `run` straight to ESCALATE; stages without a policy end in `OUT FAILED`.
Two pieces cooperate:
- **The server's rule file** `rules.json` (`yukta.supervisor.rules.file`; `yukta-worktree.sh` and
  `yukta-native.sh` set it) restarts a stage on RETRY while `attempt < 2`. A server without it still works:
  an unrestarted RETRY is treated as an escalation.
- **The driver** (`run_stages`, called by `yukta-check.sh`) follows the newest execution of each stage
  workflow (a restart is a new execution), reads the outcome from the terminal's status metadata, and on
  ESCALATE runs the fixer (two attempts) and starts the stage again. After the last stage it re-runs every
  task once if anything was healed (`yukta-heal.sh verify`), and prints `STAGE …` lines and the `RESULT` line.
  With `--detach` the driver runs in the background: `STARTED session=<s> log=<file>`, then
  `yukta-watch.sh <s> <file>` follows the log to the `RESULT` line.
| stage | `auto` | `claude` | in `heal.json` |
|---|---|---|---|
| spotless | `spotlessApply` | yes | |
| compile, tests, coverage, style, spotbugs, cli | | yes | |
| licenses, security | | | no entry: plain fail-fast |

`session.json` stays the linear spec (and the `--no-heal` workflow). `test-self-heal.sh` checks the generated
stage workflows offline.

Each fix step is `yukta-heal.sh <stage> <attempt> <mode>`, run by the `apply` node (mode `auto`, which exits non-zero when it did not repair the stage) or by the driver (Claude). For a Claude
attempt it re-runs the stage to get the cause fresh (and stops there if it passes now), builds a short brief
(the problem lines only, the failing test classes, the working-tree diffstat, the previous attempt's
summary), runs `claude -p` (Sonnet, `acceptEdits`, tools limited to Read/Edit/Write/Grep/Glob and
`./gradlew`, no `git commit`/`push`, budget `YUKTA_HEAL_BUDGET` = $1), runs `spotlessApply`, and judges
the attempt by what changed on disk: touching `config/`, `build-logic/`, `gradle/`, `.github/`, `.claude/`
or this folder, adding a suppression (`@SuppressFBWarnings`, `NOPMD`, `CHECKSTYLE:OFF`,
`@SuppressWarnings("PMD")`, `@Disabled`), removing assertions, or changing nothing marks it `fixed=false`
(`summary=GUARD: …`). It never reverts (the tree may hold your own edits) and never commits.
- `--fixer fresh|fork|auto`: attempt 2 forks the calling session (`CLAUDE_CODE_SESSION_ID`) so it knows
  what the change was; `auto` is fresh then fork, and fresh when there is no session. `--no-heal` turns it off.
- It prints `HEAL <stage> attempt=<n> mode=<m> fixed=<bool> files=<n> cost=$<x> summary=<text>` (shown by
  `--quiet`) and writes `.yukta-dev/heal/<session>/<stage>-<n>.json`; `RESULT` gains `healed=<stage:step,…>`.
- The server needs `YUKTA_PROCESS_ALLOW_ANY=true` and a logged-in `claude` on its PATH (not `--bare`, which
  loses the login). `healed=` comes from the records, not from node statuses. Which exit a stage took is its
  `OUTCOME_TERMINAL`'s `reached`/`outcome` metadata.

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

## Using the CLI directly
```bash
export YUKTA_SERVER_URL=http://localhost:9101     # printed by yukta-worktree.sh
yukta-cli session list
yukta-cli -o json workflow status <session> <execution>
yukta-cli logs <session> <execution>
yukta-cli plugin list
```
The server also exposes the web UI and MCP on the same port.

## Notes
- State lives in `.yukta-dev/` (git-ignored): `<name>.env`, `.pid`, `.log`, and `current.env` (last server).
- Per-worktree isolation comes from `SERVER_PORT` and `YUKTA_SESSION_BASE_DIR`.
- Sessions are in-memory: restarting the server forgets them; `yukta-check.sh` re-applies each run.
- Edit stages in `session.json`; `__PROJECT_PATH__` is substituted at run time.
- Handy Claude Code permissions: `Bash(examples/11-dogfood/scripts/*)`, `Bash(yukta *)`.
