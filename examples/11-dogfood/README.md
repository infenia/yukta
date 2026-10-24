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
starts it and returns: `STARTED session=<s> log=<file>` (staged check) or `STARTED execution=<e> session=<s>`;
`yk watch <s> <file|e>` follows it (`--all`: every event, for humans).
Every control-bus event has a severity, `INFO`, `WARN` or `ERROR` (`GET /api/events/stream?severity=WARN`
sends that and above): a failed or timed-out node or run is `ERROR`, so is a log line with `error`,
`exception`, `failed`, `failure` or `What went wrong`; a rejected control command, a cancelled node or run
and a `warn`/`deprecat…` line are `WARN`. A failed node's status event also carries the reason
(`metadata.error`). The problems-only output is `NODE <node> <status>` for a failed node,
`ERROR|WARN <node>: <line>` (each once, 20 per node at most), `CONTROL <command> applied=false [reason]`,
`HEAL …`, `WORKFLOW <status>`, then `SLOW <node> <s>s (median <m>s, <n> runs)` for a stage over 15s and
1.5x the median of its last 10 runs at the same scope (`.yukta-dev/durations.tsv`), then `RESULT`.

Steer a run:
```bash
yk retry <e> <node>                                   # new execution from a failed stage
yk stop <e>
yk cli workflow pause <s> <e>                         # or resume
yk cli workflow node signal <e> <node> --name approval --decision approve
yk cli workflow node breakpoint <e> <node> --field payload.status --equals error
```

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
Exit 0 only when the run ends `SUCCESS` (or `COMPLETED`); the last line is
```
RESULT status=FAILURE failed_node=style healed=spotless:auto execution=<id> session=check-<branch>-<hash>
```

## Self-healing check
`yk check` runs the staged `gradle-check` as one small workflow per stage (`stage-<stage>`, built by
`stage_workflows` in `scripts/_lib.sh` from the `gradle-check` stage table, so `-m` works). Each stage
has several explicit exits, named by an `OUTCOME_TERMINAL` whose outcome the supervisor and the driver act on:
```
run ──success──────────────▶ OUT SUCCESS               the driver starts the next stage
 └failure▶ apply ─success▶ OUT RETRY (restartFrom run)  the server's rule restarts the run from `run`
              └failure───▶ OUT ESCALATE                 the driver calls the Claude fixer, then restarts the stage
```
Stages with only a Claude fix go from `run` straight to ESCALATE; stages without a policy end in `OUT FAILED`.
- **The server's rule file** `rules.json` (`yukta.supervisor.rules.file`, set by `yk up`) restarts a stage
  on RETRY while `attempt < 2`.
- **The driver** (`run_stages`) follows the newest execution of each stage workflow (a restart is a new
  execution), reads the outcome from the terminal's status metadata, and on ESCALATE runs the fixer (two
  attempts) and starts the stage again. After the last stage it re-runs every task once if anything was
  healed (`yukta-heal.sh verify`), and prints `STAGE …` lines and the `RESULT` line.

| stage | `auto` | `claude` | in `heal.json` |
|---|---|---|---|
| spotless | `spotlessApply` | yes | |
| compile, tests, coverage, style, spotbugs, cli | | yes | |
| licenses, security | | | no entry: plain fail-fast |

`session.json` stays the linear spec (and the `--no-heal` workflow). `test-self-heal.sh` checks the generated
stage workflows offline.

Each fix step is `yukta-heal.sh <stage> <attempt> <mode>`. For a Claude attempt it re-runs the stage to get
the cause fresh (and stops there if it passes now), builds a short brief (the problem lines only, the
failing test classes, the working-tree diffstat, the previous attempt's summary), runs `claude -p` (Sonnet,
`acceptEdits`, tools limited to Read/Edit/Write/Grep/Glob and `./gradlew`, no `git commit`/`push`, budget
`YUKTA_HEAL_BUDGET` = $1), runs `spotlessApply`, and judges the attempt by what changed on disk: touching
`config/`, `build-logic/`, `gradle/`, `.github/`, `.claude/` or this folder, adding a suppression
(`@SuppressFBWarnings`, `NOPMD`, `CHECKSTYLE:OFF`, `@SuppressWarnings("PMD")`, `@Disabled`), removing
assertions, or changing nothing marks it `fixed=false` (`summary=GUARD: …`). It never reverts (the tree may
hold your own edits) and never commits.
- `--fixer auto` (default): attempt 1 fresh, attempt 2 forks the calling session (`CLAUDE_CODE_SESSION_ID`)
  so it knows what the change was; `--fixer fresh` for both. `--no-heal` turns healing off.
- It prints `HEAL <stage> attempt=<n> mode=<m> fixed=<bool> files=<n> cost=$<x> summary=<text>` and writes
  `.yukta-dev/heal/<session>/<stage>-<n>.json`; `RESULT` gains `healed=<stage:step,…>`.
- The server needs `YUKTA_PROCESS_ALLOW_ANY=true` (`yk up` sets it) and a logged-in `claude` on its PATH
  (not `--bare`, which loses the login).

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
yk cli session list                               # the CLI built with the server, pointed at it
yk cli -o json workflow status <session> <execution>
yk cli plugin list
```
The server also exposes the web UI (<http://127.0.0.1:9858/>) and MCP (`/mcp`) on the same port.

## Notes
- Machine state: `~/.local/state/yukta-dev/` (`YUKTA_DEV_HOME`); per checkout: `.yukta-dev/` (git-ignored).
- Sessions are stored under the server's `sessions/`; `yk` re-applies the session on each run.
- Edit stages in `session.json`; `__PROJECT_PATH__` is substituted at run time.
