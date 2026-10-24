# Yukta-first development loop (plan)

Status: PR1 (foundation) in progress; PR2-PR6 planned. Agreed with the maintainer on 2026-10-25.


## Context
Claude still runs `./gradlew` directly, for these reasons:
- CLAUDE.md's test tiers 0-3 (and 13 module CLAUDE.md files) tell it to.
- The `yukta` skill says "inner loop: use the Gradle tiers, not this".
- `Bash(./gradlew *)` is pre-approved, while the dogfood scripts are not.
- **The dogfood hooks do nothing when Claude starts in a module folder** (which CLAUDE.md recommends). `$CLAUDE_PROJECT_DIR`
  is then e.g. `docs/`, so `"$CLAUDE_PROJECT_DIR"/.claude/hooks/*.sh` doesn't exist, and `git status -- '*.java'` only
  sees that folder.

The dogfood server comes from the latest GitHub *release* on a random port (9101+), not from `main`. The self-heal
loop is ~120 lines of bash polling the server. Yukta is used as a flat task runner, although workflows should be
composable layers: epic → issue → implement → check → PR.

Goal:
- Every build, test and check Claude runs goes through Yukta.
- The Yukta server is built from `origin/main` and runs on fixed port **9858**.
- The check is a reusable, self-healing building block that larger workflows call.
- Claude Code hooks, skill, MCP, status line and slash commands steer and enforce this.
- The dogfood shell tooling gets smaller.

## Decisions (agreed with the user)
| Topic | Decision |
|---|---|
| Server | `origin/main` → `:boot:bootJar` + `:cli:goBuild` in a dedicated worktree, cached per SHA, `java -jar` |
| Port | **9858** on 127.0.0.1 (`YUKTA_DEV_PORT` overrides); in README + CLAUDE.md |
| Stale server | Rebuild in the background when main moves; swap only when nothing is RUNNING |
| Direct gradle/go/pnpm | PreToolUse **blocks** them from Claude's Bash tool and replies with the `yk` equivalent |
| Heal loop | Inside Yukta, as one execution with unrolled stages; the bash driver and the RETRY-restart rule are deleted |
| Control hierarchy | T0 = your Claude session (admin key, full tree control, live Monitor feed + Haiku watcher for long runs); T1 = Yukta rules (budget gate, stall, failure escalation, gradle concurrency cap); T2 = headless workers (VIEWER key). Default checkpoints: before-opus, before-implement, before-pr, between-issues |
| Fixer ladder | deterministic auto fix → fresh slim **Haiku** → **Sonnet** → **Opus** (each gets the earlier summaries + diff). Fork mode removed. In `implement-issue`, the fixer **resumes the implementer session** instead |
| Parallel analyzers | Yes, fanned out after tests; one fixer after the join |
| Extra stages | ui (pnpm lint + vitest), docs (`mint broken-links`), reuse, actionlint; all healable |
| Layering | L0 (check) + L1 (implement-issue) now; L2 (epic) right after |
| Implementer | Headless `claude -p` node in the issue's worktree; approval (WAIT_FOR_SIGNAL) before PR; notify on finish |
| CLI vs MCP | Both. The CLI is primary (start, watch, RESULT, hooks, watcher); MCP is for drill-down and steering |
| Claude extras | Prompt-intent hook, touched-module tracking, status line (project settings), slash commands |
| Providers | main-jar (default) and `release`. Deleted: native, jar, docker, worktree |
| Location / entry | Stays in `examples/11-dogfood/`; one `yk` script |
| State | `~/.local/state/yukta-dev/` machine-wide (main worktree, `builds/<sha>/`, pid/env/log, sessions). Per-checkout `.yukta-dev/` keeps heal records, durations, baselines, touched modules |

## 1. Server from main on 9858 — `yk up | down | status`
- `yk up`:
  - Take the lock `flock …/build.lock`.
  - Ensure the worktree `…/main` (`git worktree add --detach`), then `fetch origin main` and `checkout --detach origin/main`.
  - If `builds/<sha>/{yukta.jar,yukta-cli}` is missing, build it there. This reuses the ui/.gradle pnpm-toolchain copy from
    `yukta-worktree.sh:45-54`.
  - If the server is healthy at that SHA, it's a no-op.
  - If it's stale and idle (no RUNNING executions per `/api/executions`, `ExecutionSearchController`), restart on the new jar.
  - If it's stale and busy, print `STALE … swap pending`.
- Server environment: `SERVER_PORT=9858`, `YUKTA_SERVER_ADDRESS=127.0.0.1`, `YUKTA_PROCESS_ALLOW_ANY=true`, plus the session
  dir and timeout as today. No rules file needed any more.
- If the port is held by something that isn't Yukta, fail with a clear message.
- `yk up --background` is used by SessionStart: it returns immediately and builds/swaps detached.
- `yk up --release [tag]` starts the cosign-verified release on the same port. It keeps `yukta-release.sh`'s verify logic;
  the launch code moves into `_lib.sh`.
- The CLI defaults to `builds/<sha>/yukta-cli` (it matches the server). `YUKTA_BIN` selects a branch-built CLI.
- The server persists across sessions. `yk down` stops only what `yk` started.
- Reuse from `_lib.sh`: `launch_server`, `wait_healthy`, `stop_server`, `need`, `die`.
- Consequence: engine changes (PR2) reach the dev server only once merged to main. PR3+ depend on PR2 being merged.

## 2. Engine and CLI work (PR2; core / plugins / web / cli, with tests)
Evidence from the code research is in parentheses.
1. **SUB_WORKFLOW outcome to the parent.** Today the output is only `{executionId, results}` (`SubWorkflowProcessor.java:148-154`);
   the outcome lives only in OUTCOME_TERMINAL event metadata (`OutcomeTerminal.java:96-107`).
   Add the child's `status` and `outcome` (and `reason`) to the output, plus `routeByOutcome` → `success`/`failure`/
   `<outcome>` ports, so the parent can route on ESCALATE vs SUCCESS.
2. **Child events in watch.** The child gets its own id, and the event filter is exact-id (`EventsController.java:70`).
   - Record `parentExecutionId` on children (`SubWorkflowRunner.java:57`) and on restarts (`restartOf`,
     `WorkflowOrchestrator.java:355`); expose both in history/status.
   - The server event stream gets `includeDescendants`.
   - CLI: `controlbus watch --until-done` includes descendants by default (lines prefixed `<workflow>/<node>`), and
     `--follow-restarts` switches to the successor execution (`watch.go:168-171,216-248`, `status.go`).
3. **`${start.x}` templating**: the execution's start payload, resolved anywhere (`VariableResolver.java` ~:183).
   - Today `${payload.x}` is the node's input and is gone two nodes later (`VariableResolver.java:229-235`).
   - SUB_WORKFLOW passes its `payload` config (templated) as the child's start payload.
   - This is required so one check workflow can run on any checkout or worktree: `workingDir: ${start.project}`,
     `YUKTA_IMPL_SESSION: ${start.implSession}`.
4. **SUB_WORKFLOW timeout**: `timeoutSeconds` already exists (default 300, `SubWorkflowProcessor.java:42`). Verify there's no
   hidden cap below the session execution timeout and document it; our workflows set 14400.
5. **ALL_DONE join**: an end-to-end test that a failed branch behind an ALL_DONE join is visible (node FAILURE, run status
   correct), and a fix if it's silently swallowed (`StreamTopologyDecorator.java:223-233`, `WorkflowOrchestrator.java:517-546`).
6. **AGGREGATE items carry `sourceNodeId`** (`AggregateProcessor.java:101`), so the analyzer gate knows which branches failed.
- Restart lineage (part of item 2) isn't needed by the unrolled check, but it's included as decided.
- Each item has its own tests (Tier 1-2 via `yk gradle`, Tier 4 before the PR); `:cli:check` needs 100% function coverage.

## 3. The check as a building block (PR3)
One session, **`yukta-dev`**, applied by `yk` from the *current branch's* `session.json` + generator. Its workflows are
parameterized by the start payload (`project`, `implSession`, `module`), so every checkout and every issue worktree shares them.
The generator moves from `stage_workflows` to `heal_workflow` in `_lib.sh`. Scoped variants (`check@core`) are generated per
module list on demand.

**`check` graph: one execution, stages unrolled.**
- Fixers per stage come from `heal.json`: `[auto]?` then the ladder `["haiku","sonnet","opus"]`. The ladder is set globally
  in `heal.json` with per-stage overrides; e.g. `compile` may start at sonnet if measurements show Haiku rarely fixes it.
  `yk check --ladder sonnet,opus` overrides it per run.
  When the check runs under `implement-issue`, the ladder becomes `["resume"]` ×2: `--resume ${start.implSession}`
  (see §4).
- Sequential stages (spotless, compile, tests, coverage) are chains:
  - `S.run1` (CONTINUE, routeByExitCode): success → next stage; failure → `S.f1`.
  - `S.fi` = `yukta-heal.sh S <n> <mode>`: success → `S.run(i+1)`; failure → `S.f(i+1)`.
  - The last run uses failureMode ERROR, so the run fails with `failed_node=S`.
  - The attempt number and model are fixed in the graph: no runtime counting, no supervisor rule.
    Worst case per stage: `run1 → auto → run2 → haiku → run3 → sonnet → run4 → opus → run5`.
  - A healed stage reruns only itself.
  - `yukta-heal.sh` exits non-zero when `fixed=false` (today only `auto` does; `:190`).
- Analyzers run in parallel after coverage: style, spotbugs, licenses, security, cli, ui, docs, reuse, actionlint.
  - Each is ERROR mode, so its failure shows as NODE FAILURE + ERROR lines in `--quiet`.
  - `an.join` = AGGREGATE with `engine.trigger: ALL_DONE`, plus a guard edge from coverage, then
    `an.gate` (BRANCH on failed `sourceNodeId`s).
  - **One** fixer gets all failed analyzers (no two fixers ever edit the tree at once).
  - The re-run is a *single* `./gradlew --continue <failed analyzer tasks>` (+ non-gradle tools), never parallel gradle.
    Concurrent builds are only safe while compile is up to date, and each would start a 4 GB daemon.
  - Analyzers without a heal policy (licenses, security) skip the fixer and fail.
- `verify`: `yukta-heal.sh verify` with all tasks, only if anything was healed (already implemented), then `done`.
- **Extra stages** (skipped with a WARN when the tool is missing):
  - `ui`: `pnpm -C ui lint && pnpm -C ui exec vitest run`; auto fix `eslint --fix`.
  - `docs`: `mint broken-links`.
  - `reuse`: `reuse lint`; auto fix `reuse annotate`.
  - `actionlint`: local binary, else the pinned docker image from `ci.yml:80`.
  - Scoping keeps `ui`/`docs`/`cli` only when that module is in scope; reuse/actionlint run only unscoped.
- **Guard** (`yukta-heal.sh` protected paths): stage-scoped allow, so only the `actionlint` fixer may touch
  `.github/workflows/*.yml`. `REUSE.toml` stays protected for the reuse fixer.
- **`gradle-run`** (`yk gradle <args>`): the same generator with one stage (claude policy). `--no-heal` gives a single ERROR node.
- RESULT: `yk watch` = `controlbus watch --until-done --quiet`, then one RESULT builder:
  - `failed_node` (the stage name, `.runN` stripped, only when status ≠ SUCCESS)
  - `healed=` (`healed_stages`, unchanged)
  - `SLOW` (`record_durations` made branch-aware: per-node start/end instead of the gap to the previous node).
- Deleted: `run_stages`, `follow_stage`, `stage_outcome`, `start_stage`, `_history`, `stage_workflows`, `rules.json`, and
  `yukta-watch.sh`'s log-file mode. `test-self-heal.sh` is rewritten for the unrolled graph (DAG, chain shape per policy,
  a single fixer after the join, guard scoping).

## 4. Layered workflows (PR5: L1, PR6: L2)
All live in the `yukta-dev` session and reuse `check` via SUB_WORKFLOW (payload `{project: ${start.worktree}}`, timeout 14400,
routeByOutcome).
- **`implement-issue`** (`yk run issue <n>`, `/yk-pickup`), with API_TRIGGER `{issue}`:
  1. `gh issue view` checks it is assigned and Ready (else OUTCOME ESCALATE "not Ready"). Claim it as In progress
     (`.github/scripts/project-lib.sh`, as in the pickup skill).
  2. `git worktree add ../yukta-issue-<n> -b issue/<n> origin/main`.
  3. **implement** = headless `claude -p --session-id <uuid generated by the workflow>` in the worktree, with the issue body + acceptance criteria as the prompt, budget
     `YUKTA_IMPL_BUDGET`, and the same rules/guards and `YUKTA_HEAL_FIXER=1` env as the fixer.
  4. SUB_WORKFLOW `check` with payload `{project, implSession}`. Its fixers resume the implementer (same model, warm cache,
     knows the change) for up to 2 attempts after the deterministic fixes.
  5. On failure → OUTCOME ESCALATE: the issue is set back to Ready and the worktree is kept.
  6. On success → **WAIT_FOR_SIGNAL `approve-pr`** (`yk approve <exec> [--reject]`, or the UI) → `gh pr create --draft` with
     `Fixes #<n>` and the RESULT line → NOTIFY_TERMINAL / push.
- **`epic`** (PR6), with API_TRIGGER `{epic}`:
  - `gh` sub-issues → FILTER Ready+assigned → SPLITTER → SUB_WORKFLOW `implement-issue` per item, **in parallel, limit 2**
    (each issue has its own worktree, so its checks don't collide) → AGGREGATE → summary + NOTIFY.
- The `pickup` skill becomes a thin front end to `yk run issue|epic`. Its claim/PR steps move into the workflow.

## 5. `yk` command surface (replaces yukta-check/watch/cli/server/stop scripts)
```
yk up [--background|--release [tag]] | down | status        server (url, sha, stale?, running executions)
yk check [-m mod]... [--only stage,..] [--no-heal] [--ladder haiku,sonnet,opus] [--detach]   watch is the default
yk stats                                                    $ and cache-hit ratio per stage/model from heal records
yk gradle <args…>                                           any gradle args as one healable stage (tiers 0-2)
yk run issue <n> | epic <n> [--detach]                      layered workflows (PR5/6)
yk watch <exec> | yk retry <exec> <node> | yk stop <exec> | yk approve <exec> [--reject]
yk finding …            (report-finding.sh behind it)       yk cli <args…>   raw passthrough
yk statusline           one cached line (<50 ms)
```
The full-log default of `yukta-check.sh` (`mode=logs`, which the skill forbids) is gone. `-m` is repeatable.

## 6. Claude Code integration
- **Hook paths**: every hook becomes `bash -c 'exec "$(git rev-parse --show-toplevel)/.claude/hooks/yk-hook.sh" <event>'`,
  and the scripts use `git -C "$root"`. One `yk-hook.sh` replaces dogfood-baseline/nudge.
  - The untracked `post-tool-call.py` local hook has the same bug. Tell the user; it isn't ours to change.
- **SessionStart**:
  - Record the baseline.
  - Run `yk up --background`.
  - Write `YUKTA_SERVER_URL=http://127.0.0.1:9858` to `$CLAUDE_ENV_FILE`.
  - Add one `additionalContext` line: server state, plus "build/test/check via the yukta skill (`yk`); direct gradle is blocked".
- **UserPromptSubmit**: on `test|build|compile|check|gradle|lint|spotless|checkstyle|pmd|spotbugs|coverage|CI|failing|issue|epic`,
  add one line naming the yukta skill.
- **PreToolUse(Bash)**: deny `./gradlew|gradlew|gradle`, `go test|vet` and `pnpm (test|lint|exec vitest|exec eslint)`, with the
  translated `yk` command as the reason.
  - Exempt when `YUKTA_HEAL_FIXER=1` (exported to headless fixer/implementer) or `YUKTA_DOGFOOD=off`.
  - Playwright and `pnpm dev` stay allowed.
- **PostToolUse(Edit|Write)**: map the edited path to its Gradle module (nearest `build.gradle.kts`) and append it to
  `.yukta-dev/touched-<session>`.
- **Stop**: nudge once per change set with `yk check -m <touched…>`. Skip when the last RESULT for the same diff hash was SUCCESS.
- **Status line** (`.claude/settings.json`): `yukta :9858 main@ab12cd3 ✓ | last: core SUCCESS (healed style)`.
- **Slash commands**: `.claude/commands/yk-check.md`, `yk-up.md`, `yk-finding.md`, `yk-pickup.md` (thin, `$ARGUMENTS`).
- **MCP**: `.mcp.json` gets `"yukta": {"type":"http","url":"http://127.0.0.1:9858/mcp"}`.
  - The skill names the drill-down tools (`get_execution_logs` regex+tail, `get_execution_result`, `control_node`, `signal_node`,
    `set_node_breakpoint`, `list_pending_approvals`).
  - Known issue: output validation rejects running executions. File it as a dogfood issue, or fix it in PR2 if small.
- **Permissions** (shared settings): allow `Bash(examples/11-dogfood/yk *)` and the read-only `mcp__yukta__*` tools.
  Suggest the user drop `Bash(./gradlew *)` from their local settings.
- **Watcher subagent**: `yukta-run-watcher.md` is updated to `yk watch`. It's simpler now that one execution id (plus
  descendants) covers the whole run.

## 7. Skill and docs
- **SKILL.md**: ~70 lines.
  - Description front-loads the triggers (any build/test/compile/gradle/check/lint/coverage/CI failure/"does it pass",
    dev server, watch/retry/approve a run, implement an issue or epic, dogfood finding).
  - Body: the `yk` table, reading RESULT, heal review, the token-budget rule, CLI vs MCP roles.
  - Detail goes to `reference/{heal,dogfood-loop,server,layered-workflows}.md`.
- **CLAUDE.md**: the test-tier table keeps its tiers with `yk` commands, plus the port-9858 line and the "direct gradle is blocked"
  note. Apply the same swap in the 13 module CLAUDE.md files and `.claude/rules/*.md`.
- Also update: root README "Developing Yukta" (port 9858), the rewritten `examples/11-dogfood/README.md`, `pickup/SKILL.md`,
  `.github/PULL_REQUEST_TEMPLATE.md`, `perf/KNOWN-ISSUES.md`. Leave `design/` history alone; add
  `design/plans/yukta-dev-loop.md` (this plan).

## 8. Dead code removed (shell tooling only)
- Deleted files: `yukta-native.sh`, `yukta-docker.sh`, `yukta-worktree.sh`, `yukta-stop.sh`, `yukta-server.sh`, `yukta-check.sh`,
  `yukta-watch.sh`, `yukta-cli.sh` (all folded into `yk`), `dogfood-baseline.sh` and `dogfood-nudge.sh` (→ `yk-hook.sh`).
  `rules.json` loses its RETRY-restart rule and keeps only the T1 policies (§10).
- Removed from `_lib.sh`: `free_port`, `warn_if_stale_native`, the "older version" branch of `stop_server`, the bash driver
  (§3), and the duplicated RESULT builders.
- Kept: `report-finding.sh`, `yukta-heal.sh`, `yukta-release.sh` (verify only), `test-self-heal.sh`, and `run.sh` +
  `session.json` (the public example).

## 9. Token efficiency (applies to every headless Claude: fixer, implementer)
Principle: spawn Claude only when nothing cheaper works, and make every spawned session share one byte-identical cached prefix.
1. **Cheaper steps first** (all in the graph, before any Claude node):
   - deterministic fixes (`spotlessApply`, `eslint --fix`, `reuse annotate`);
   - a "passes now" re-run (flaky, no spend);
   - one fixer for *all* failed analyzers;
   - gradle `--continue`, so a single attempt sees every failure.
2. **One shared slim prefix**. A single `yk_claude()` launcher in `yukta-heal.sh` is used by every headless node:
   - `--setting-sources project`: none of the user's plugins, skills or settings;
   - `--strict-mcp-config` (no MCP tool schemas);
   - `--disable-slash-commands`;
   - `--tools Read,Edit,Write,Grep,Glob,Bash` (fixed order);
   - `--exclude-dynamic-system-prompt-sections` (cwd and git status leave the system prompt, so worktrees share the cache);
   - a pinned `--model`.
   - One **constant** `--append-system-prompt` (`examples/11-dogfood/fixer-rules.md`). Everything per-run (stage, problems,
     diff stat) goes in the user prompt only.
   - Not `--bare`: it drops the login.
   - The project hooks still load, which keeps the PreToolUse guard. `YUKTA_HEAL_FIXER=1` lets gradle through.
3. **Fork mode removed.** Today it forks your Opus session under Sonnet, which means no cache and the whole context re-sent
   every turn. It's replaced by the Haiku → Sonnet → Opus ladder of fresh slim sessions; each rung costs one small
   prefix cache write per model. In issue runs, the fixer resumes the implementer (its own cache is warm).
   `CLAUDE_CODE_SESSION_ID` is no longer passed anywhere. `--fixer` becomes `--ladder`.
4. **Compact briefs**:
   - problem lines + failing tests + file:line, never logs;
   - the fixer uses `codegraph explore "<symbols>"` (one call) before Read loops;
   - `--max-turns` and `--max-budget-usd` caps.
5. **Model routing**: Haiku for the watcher/triage, Sonnet for fixes, Opus only for `implement-issue` (configurable).
6. **Parallel epics warm the cache first**: start issue 1, wait for its first response (the prefix is now cached), then start
   the others. They all hit the same prefix.
7. **Measure it**:
   - each heal/implement record stores `usage` (input, cache_creation, cache_read, output, cost) from `--output-format json`;
   - `yk stats` prints the cache-hit ratio and $ per stage;
   - targets: ≥70% cache-read on the second and later sessions within the TTL, and a typical heal attempt under $0.30.
   - A regression gets filed as a dogfood finding.
8. **Main session stays lean**: runs are watched by the Haiku watcher or a single `--quiet` RESULT, and MCP is used only for
   targeted drill-down.

## 10. Control hierarchy: T0 has full control of the tree
Every run is a tree (epic → issue → check → stages, via `parentExecutionId` from §2). Authority runs top-down.

| Tier | Who | Key / authority | Role |
|---|---|---|---|
| **T0** | the interactive Claude session (and you, in the UI) | `admin` key | Full control of any tree: start, pause/resume (tree or node), stop (drain/cancel/kill), skip, retry, restart-from, breakpoints, step mode, signals/approvals, workflow rollback |
| **T1** | Yukta supervisor rules + WAIT_FOR_SIGNAL gates | server-side | Autonomous policies; escalate to T0 by pausing and raising a signal |
| **T2** | headless implementer/fixers | `worker` key, **VIEWER** | Read their own status/logs; no control. They report via exit code + JSON only |

**Keys**: on first run, `yk up` generates named keys (`ApiKeys.KeyEntry`, hashed `sha256:` form in the server config;
plaintext at mode 600 in the state dir).
- `yk` and the MCP entry use `admin` (`--token-file`; `.mcp.json` header from an env var).
- Headless nodes get `YUKTA_TOKEN=<worker>`.
- The server stays loopback-only. Once keys exist, a no-key request is rejected.

**Engine/CLI additions (added to PR2)**:
- **Tree-scoped control**: control commands accept `scope: TREE` and apply to the execution and all descendants
  (`ControlCommand`, the control gateway, the REST control endpoints, MCP `control_workflow`/`control_node`).
  `get_execution_tree` is added as a VIEWER MCP tool, with REST and CLI equivalents.
- **Tree event stream**: `includeDescendants` (already in §2.2). Each event carries `rootExecutionId` and `depth`.
- **Stall event**: a node RUNNING with no output/heartbeat (`ControlHeartbeat`) for `engine.stallAfterSeconds` emits
  `NodeStalled` (WARN), which rules and `--quiet` can see.
- **Concurrency groups**: the node policy gets `engine.concurrencyGroup: gradle` with a server-wide
  `yukta.concurrency.groups.gradle=2`. Excess nodes wait (QUEUED, visible) instead of starting more 4 GB daemons.
  Fallback if this grows too large: a flock semaphore in the stage command.

**T1 policies** (rules file + gates in the generated graphs; `rules.json` returns, holding policies only):
- **Budget gate**: before every Claude node, `yk-budget` checks the tree's spent $ (heal/implement records) against
  `YUKTA_BUDGET_ISSUE` / `YUKTA_BUDGET_EPIC`; over budget → WAIT_FOR_SIGNAL `budget` (approve = raise, reject = stop subtree).
- **Stall**: a rule on `NodeStalled` → PauseNode + a signal to T0 (`stall`).
- **Failure escalation**: a rule counts the epic's issue runs ending ESCALATE (`count: 2`) → PauseWorkflow (tree) +
  signal `epic-escalation`.
- **Concurrency cap**: the gradle concurrency group above.

**Default checkpoints** (WAIT_FOR_SIGNAL nodes in the graphs; `--no-pause <name,…>` drops them per run):
- `before-opus`: the ladder pauses before the Opus rung. T0 can continue, take over (stop the subtree, fix it itself, then
  retry the stage), or stop.
- `before-implement`: the brief and worktree are ready; T0 may edit `.yukta-dev/issue-<n>/brief.md` and then approve.
- `before-pr`: the approval already planned.
- `between-issues`: in an epic, each finished issue (with parallel=2, as each slot frees) waits for T0 before the next starts.

**T0 monitoring and control surface**:
- **Live feed**: `yk watch --tree <root> --quiet` under the **Monitor** tool. Only attention events reach the session:
  NODE FAILURE/ERROR, WARN, STALL, BUDGET, APPROVAL pending (with the signal name), CONTROL refused, HEAL, SLOW, and the
  WORKFLOW end + RESULT. The session keeps working and acts as events arrive.
- For multi-hour epics, the **Haiku watcher** triages and returns only on escalation or an approval request.
- `yk tree <root>` prints a compact snapshot, one line per execution: workflow, status, current node, $ so far, pending signal.
- `yk pause|resume|stop <exec> [--tree] [--mode drain|cancel|kill]`, `yk skip|retry <exec> <node>`,
  `yk break <exec> <node> [--field F --equals V] [--clear]`, `yk step <exec> on|off`, `yk approve|reject <exec> <signal>`,
  `yk signals` (pending, tree-wide).
- MCP gives the same with structured output, for drill-down (`get_execution_tree`, logs regex+tail, `control_*` with
  `scope: TREE`).
- Status line: `yukta :9858 main@ab12cd3 ✓ | epic#12: 2 running · 1 awaiting before-pr · $1.40`.
- The skill gets a "Steering a tree" reference (`reference/control.md`): when to pause vs stop, how to answer each
  signal, and that only T0 issues control.

## Delivery (each PR branches from `main`; RESULT line in each PR description)
1. **PR1 – foundation**: `yk` (up/down/status/check/gradle/watch/retry/finding/cli), the main-jar server on 9858 + release, the
   hook path fix, `yk-hook.sh` (SessionStart/Stop/PreToolUse redirect), skill + CLAUDE.md + module docs, README port, and the
   dead-script removal. The heal still uses the current driver.
2. **PR2 – engine/CLI**: §2, items 1-6, plus the §10 engine additions: tree-scoped control, `get_execution_tree`,
   root/depth on events, the `NodeStalled` event, concurrency groups, and the MCP running-execution validation if small.
   This is the largest PR. If review gets heavy, split it into 2a (SUB_WORKFLOW outcome, lineage, `${start.x}`, watch tree)
   and 2b (tree control, stall, concurrency groups).
3. **PR3 – check as building block**: the `yukta-dev` session, the unrolled heal graph with the model ladder + slim launcher
   (§9), parallel analyzers, extra stages, the guard change, removal of the bash driver, and keys/tiers (T0 admin, T2 worker),
   the budget gate, the `before-opus` checkpoint, the stall rule and the gradle concurrency group. Needs PR2 merged (the
   server is built from main).
4. **PR4 – Claude extras + T0 surface**: intent hook, touched modules + scoped Stop nudge, status line (with the tree summary),
   slash commands, MCP + permissions, `yk tree|pause|resume|stop|skip|break|step|approve|signals`, `yk watch --tree` under
   Monitor, and the skill's `reference/control.md`.
5. **PR5 – `implement-issue`** + `yk run issue` + `/yk-pickup`, the `before-implement` and `before-pr` checkpoints, the
   fixer resuming the implementer, and notify.
6. **PR6 – `epic`**: parallel with a limit of 2, the `between-issues` checkpoint, the failure-escalation rule, and warm-then-fan-out.

## Verification
- **PR1**:
  - From a feature branch, `yk up`, then `yk status`: the SHA equals `git rev-parse origin/main` and
    `curl 127.0.0.1:9858/actuator/health` is UP. A second `yk up` is a no-op. With `YUKTA_DEV_MAIN_REF=<older sha>` then back,
    the staleness swap happens only while idle.
  - Hooks fed JSON on stdin: `./gradlew :core:test --tests X` → deny + `yk gradle …`; with `YUKTA_HEAL_FIXER=1` → allow.
  - Start `claude` in `docs/`: the SessionStart context line appears, and the Stop nudge fires after an edit.
  - `yk check -m core` prints RESULT. `shellcheck` passes on all scripts. `test-self-heal.sh` passes.
  - Skill-trigger check: 5 fresh-session prompts ("run core tests", "does the build pass", "fix checkstyle", …) all invoke
    the yukta skill and no direct gradle.
- **PR2**: module tests via `yk gradle` (Tier 1-2), then `yk check`. New acceptance tests:
  - a parent → SUB_WORKFLOW child reaching ESCALATE routes to the parent's failure port;
  - `watch --until-done` shows the child's events and ends with the parent;
  - `${start.project}` resolves in a third node;
  - an ALL_DONE join with a failed branch reports it;
  - `pause --tree` on a parent pauses a running child and resumes both, and `stop --tree --mode cancel` ends the whole tree;
  - a VIEWER key is refused `control_node`;
  - a silent node emits `NodeStalled` after `stallAfterSeconds`;
  - 3 nodes in a concurrency group of 2 run at most 2 at a time.
  `:cli:check` at 100% function coverage.
- **PR3**: `test-self-heal.sh` checks the graph shape. Seeded failures on a scratch branch:
  - a spotless break → `healed=spotless:auto`;
  - a checkstyle + spotbugs violation → one analyzer fixer, `healed=analyzers:fix1`;
  - a licenses failure → fail fast, no fixer cost;
  - a trivial checkstyle issue is fixed at the Haiku rung (`healed=analyzers:haiku`). `yk stats` shows usage per rung, and
    the second same-model session within the TTL reads ≥70% from cache;
  - a clean tree → SUCCESS with no verify.
  Compare wall-clock against today's staged check (durations.tsv).
  - T1 policies:
    - with `YUKTA_BUDGET_ISSUE=0.01`, the budget gate pauses at the first Claude node with a `budget` signal;
    - `yk approve` resumes it;
    - a seeded Haiku+Sonnet failure stops at `before-opus`.
- **PR4**: status line runs in under 50 ms; `/mcp` shows yukta connected; each slash command runs; touched modules scope the nudge.
  - From a Claude session, `yk watch --tree` under Monitor delivers only attention events. During a running check:
    `yk pause --tree`, `yk tree`, `yk break`, then `yk resume`, all applied and visible in the UI.
- **PR5/6**: on a throwaway `dogfood-test` issue: `yk run issue <n>` → worktree, implement, check, approval wait →
  `yk approve` → draft PR with RESULT → notify. Reject path: the issue goes back to Ready. An epic with 2 sub-issues runs both,
  limited to 2 concurrent.
  - The `before-implement` brief edit is picked up by the implementer.
  - `between-issues` pauses after each issue.
  - Two seeded ESCALATEs pause the epic tree with `epic-escalation`.
  - The second parallel issue's first request reads the slim prefix from cache (`yk stats`).
