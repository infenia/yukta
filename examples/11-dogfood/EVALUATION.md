# Evaluating the dogfood loop: how to run and read an evaluation

This guide explains how to measure what the dev loop (`yk`, `yk run issue`, the fixers) costs in tokens and dollars, whether the output is good, and where the waste is. It covers the two ways of evaluating, step-by-step runbooks, how to read the numbers without fooling yourself, and what to do when something breaks.

- **Operating the loop and the evaluations step by step: [RUNBOOK.md](RUNBOOK.md)**
- Harness reference (files, settings, `grade.env`): [eval/README.md](eval/README.md)
- The first evaluation, with its data and report page: `eval/results/2026-10-25-1207/`
- The plan for the continuous loop: `design/plans/dogfood-improvement-loop.md` (added with PR #1266)

## 1. What an evaluation answers

Every number this guide produces serves one goal and five questions (the Goal-Question-Metric method: a metric without a question is not collected).

**Goal:** lower the tokens and dollars per *delivered, correct* change through the dogfood, without losing quality or speed, and find the waste inside the loop itself.

| Question | Metrics that answer it |
|---|---|
| Q1. What does a delivered change cost, by method and size? | cost, tokens by category, main session versus sub-flows, turns, wall time |
| Q2. Where is the waste inside a run? | fixer attempts outside the issue's module, no-change attempts, cache re-writes, restarts, tool use |
| Q3. Is the output good? | delivered (`RESULT`), hidden test, CI, review comments, human commits before merge |
| Q4. Does a loop change help? | the same metrics grouped by the loop's commit |
| Q5. Is the analyzer useful? | proposals approved against filed, duplicate rate, cost per run |

## 2. Pick the right tool

| | **Paired evaluation** (`eval/eval.sh`) | **Continuous loop** (`yk pick`) | **Golden set** (planned) |
|---|---|---|---|
| What it does | Runs one issue in several methods, in scratch clones, with stubbed `gh` | Runs your real issues one by one, one random method each, real PRs | Replays a few frozen issues in all methods, repeated, on each major loop change (`eval/golden.sh`) |
| Compares methods on | the same issue (paired) | different issues (random, balanced by size) | the same issues (paired, repeated) |
| Touches GitHub | never | yes (claims the issue, opens a draft PR) | never |
| Needs | a grader per issue | nothing extra | a grader per issue |
| Best for | a controlled comparison, checking a loop change, finding root causes | the day-to-day stream and the analyzer's findings | regression checks of the loop itself |
| Status | available (PR #1252) | `yk pick`, `yk next`, `yk loop` (PR #1266), method A (PR #1267), the analyzer (PR #1268), method C (PR #1269), `yk loop report` / `sync` / `themes` (PR #1270) | available (this PR: `eval/golden.sh`) |

Rule of thumb: use the **paired evaluation** when you need a trustworthy comparison or are verifying a fix to the loop; use the **continuous loop** when you want the loop to improve itself as you work through issues.

## 3. Concepts you need

- **Methods (arms).** Two arms, A and C. (Arm B, C without the index and with fixers that resumed the implementer, is retired: its first-evaluation runs stay archived, and its ledger records are relabelled C with `was: B`.) **A**: one Claude Code session fixes the issue itself; Yukta only checks (`yk gradle`, `yk check --no-heal`). **C**: the same session is an *orchestrator* that starts `yk run issue`; slim headless sessions (an implementer with a code-navigation index, and fixers chosen by a triage that start fresh) do the work. The index is the IDE index if you opted in (`YUKTA_NAV_IDE=open`), else codebase-memory-mcp, else CodeGraph. The backend that ran and the time it took to index are recorded; compare C only within one backend.
- **Main session versus sub-flow.** The main session is your Claude Code setup (plugins, skills, MCP servers). Sub-flows are the headless sessions Yukta starts (`yk_claude` in `scripts/_claude.sh`): six built-in tools, no plugins, a constant system prompt. Arm C's cost is the sum of both.
- **Tokens by category.** *Fresh input*, *cache write* (storing new context, a little dearer), *cache read* (re-reading stored context, about a tenth of the price), *output*. Every turn re-sends the conversation, so most tokens are cache reads; a high read share is good. A turn that writes a lot where it should read means the cache was not reused.
- **Correct is not delivered.** *Correct* = the hidden test and the suite pass on the code. *Delivered* = the workflow itself ended `SUCCESS` (a branch ready for a draft PR). A run can hold correct code and still end `ESCALATE`.
- **Grader.** Code that decides whether an issue is fixed, written against the behaviour the issue asks for and never shown to Claude. Check it before you spend money: it must fail on the base commit and pass with a reference fix.
- **Size.** S, M or L from a deterministic score of the issue text (`scripts/issue-size.sh`); draws are balanced inside each size.
- **Loop commit.** The commit of the dogfood scripts a run used. Compare runs only within one loop commit, and keep runs of issues that change the loop out of method comparisons.

## 4. One-time setup

Check each line before the first run.

- `claude` is logged in; `jq`, `git`, `curl`, `flock`, `python3` are installed; `go` if the issue's grader uses it; `gh` is logged in with the project scope (`gh auth refresh -s project`) for anything that touches the project board.
- **The dev server is running and built from the code you are testing.** `yk up` builds `origin/main` on port 9858. To test unmerged engine or `yk` changes, build a separate server from your branch and use the same environment for the harness:

  ```bash
  export YUKTA_DEV_HOME=$HOME/.local/state/yukta-dev-mybranch YUKTA_DEV_PORT=9866 YUKTA_DEV_MAIN_REF=my-branch
  yk up            # builds from my-branch on port 9866, separate state
  # ... run the evaluation in this same shell ...
  yk down          # when you are done
  ```

  A server built from another commit than the evaluation's base measures the wrong code. `yk status` shows what is running.
- **Money.** A real run spends up to `EVAL_BUDGET` (default $5) for the main session and, in arm C, up to the same per implementer or fixer session. At the first evaluation's costs ($0.35 to $4 per run) three runs per arm cost roughly $15 to $30. Decide the limit before you start.
- **Workspace trust.** Each scratch clone is a new folder, so every session prints "workspace not trusted: ignoring `permissions.allow` entries". It is harmless: tools are passed explicitly, and it affects both arms alike.

## 5. Runbook 1: a paired evaluation of one issue

Work in `examples/11-dogfood/eval`.

### 5.1 Prepare the issue (once per issue)

1. **Choose the issue.** Small, clearly right or wrong, unfixed on the base commit, with a known reference fix. Mixed first-run outcomes make the best test (an issue every method solves, or none does, tells you little).
2. **Save it.** `./eval.sh fixture 1234` writes `issues/1234/issue.json` (the stub `gh` serves it, so the real issue is never touched).
3. **Write `issues/1234/grade.env`:** `MODULE` (the issue's module; arm A's check scope), `HIDDEN_DEST` (where the grading test is copied), `HIDDEN_CMD`, `SUITE_CMD` (shell commands run in the repo root). Copy `issues/1207/grade.env` as a template.
4. **Write the grading test** in `issues/1234/`. Test the behaviour the issue asks for, through the most stable entry point; avoid anything a solver would naturally rename.
5. **Validate it.** On a scratch copy of the base commit it must **fail**; with a reference fix applied it must **pass**, and the existing tests must still pass. Keep the reference fix as `reference-fix.patch` next to it. Skip this and a grader that always passes (or always fails) will quietly ruin the evaluation. `./eval.sh validate` does exactly this (it applies `reference-fix.patch` to a clone of the base commit and runs the grader on both) and exits non-zero when the grader is not sound.

### 5.2 Check the harness (free)

```bash
./selftest.sh        # fake claude, dummy issue, no server, no spend: run directory, stubs, usage, grading, report
```

Run it after any change to `eval.sh`.

### 5.3 Run

```bash
export EVAL_ISSUE=1234                          # default: 1207
export EVAL_BASE=$(git rev-parse origin/main)   # pin ONE base commit for every run (default: fetch origin/main)
./eval.sh run A 1
./eval.sh run B 1
./eval.sh run A 2
./eval.sh run B 2
./eval.sh run A 3
./eval.sh run B 3
```

- **Alternate the arms** (A1 B1 A2 B2 ...) so neither always runs first and prompt-cache carry-over does not favour one. Run **serially**; parallel runs distort wall time and share a Gradle daemon.
- Three runs per arm shows a gap, not a precise number. Add runs if the ranges overlap.
- `eval.sh run X n` **deletes and rebuilds** `runs/Xn`. Use a new number to keep an earlier run.
- A B run takes about 8 to 17 minutes and an A run about 1 to 3 minutes; the orchestrator waits in the foreground (10-minute Bash timeout) because a headless `claude -p` is not woken by background tasks.
- If a run dies midway, `./eval.sh collect X n` rebuilds `result.json` from the run's files without running Claude; `./eval.sh grade X n` only re-grades.

### 5.4 Read the results

```bash
./eval.sh report                          # per run and per arm: median [min..max]
./split.sh                                # arm C: implementer, fixers inside the module, fixers elsewhere
python3 -I cache_trace.py B1 --summary    # turns that re-wrote context (per session)
python3 -I cache_trace.py B1              # every turn: write, read, pause since the last turn
```

For each run also read **`result.json` → `answer`**: the end of the main session's reply, with the workflow's `RESULT` line. Count only `status=SUCCESS` as delivered. Open the heal records in `runs/B1/yukta-issue-<n>/.yukta-dev/heal/check/*.json` (stage, attempt, mode, cost, files changed, the fixer's summary) and the session transcripts to see what the sessions did; reading transcripts is the single most useful habit (§8).

### 5.5 Archive and report

1. Copy the small files of the runs into `results/<date>-<issue>/runs/` (`result.json`, `main.json`, `base`, `implement.json`, the `heal/check/*-N.json` records) and scrub local paths (see `results/2026-10-25-1207/` for the shape; `.gitignore` there re-includes the `.yukta-dev` folders).
2. Put what the records lost into `results/<date>-<issue>/extra.json` (a restarted run overwrites the earlier run's records; the transcripts still hold it: `cache_trace.py` prints the first run's tokens).
3. `python3 -I report/make_report.py results/<date>-<issue>/runs out.html` builds the report page. Its text is written for the first evaluation: adapt `report/template.html` for another issue, or write up the tables from `eval.sh report` and `split.sh` directly.

## 6. Runbook 2: the continuous loop (`yk pick`)

Available since PR #1266 (method A since #1267, the analyzer since the analyzer PR). Method C replaced B (the two were merged into C, #1275). `yk loop sync`, `yk loop report` and `yk loop themes` (this PR, below) read what the loop collected. The golden set comes later (see the plan). With A and C enabled the draw is random between the two methods.

```bash
yk next                       # assigned, Ready issues by priority with size S/M/L
yk pick 1234 --dry-run        # size, loop-changing flag, the drawn method; nothing is written
yk pick 1234                  # draw (ledger first), run, record the outcome
yk loop total                 # $ spent of the $60 budget
yk loop ledger                # one line per draw and outcome
```

How it protects the experiment:

- The **draw is written to the ledger before the run**, so the assignment cannot depend on how the run goes. Draws are seeded and block-randomized inside the size stratum, so methods stay balanced with few issues.
- A second `yk pick` of the same issue **keeps its method**; you cannot re-roll. `--method X` forces one, recorded as `forced` and left out of comparisons.
- **Loop-changing issues** (their text names `examples/11-dogfood`, `yk`, `build-logic`, the heal or issue scripts, `controlbus`; or label `loop-changing`) are flagged: the loop moves under them, so keep them out of method comparisons. Label `loop-neutral` overrides the flag.
- The **budget** is $5 per Claude session and **$60 in total**; at $60 `yk pick` refuses and tells you to review. That review is the point to decide whether to continue.
- An issue must be open, assigned to you and **Ready** on the project before anything is drawn; an ineligible issue uses no slot.

After every pick the **analyzer** reads the run and files what it finds (below); `yk analyze <draw-id> [--dry-run]` re-reads any run.

What a run leaves behind: a `draw` record and an `outcome` record in `$DEV_HOME/loop/ledger.jsonl`, the main session's output under `$DEV_HOME/loop/runs/<id>/`, and, for B, the issue worktree (`../yukta-issue-<n>`), a draft PR, and the heal and implement records. After the PR is reviewed, the outcome proxies (CI, review comments, human commits, merged) are what tell you whether the change was good; they are filled by `yk loop sync` (planned).

### 6.1 What the analyzer does with a finished run

1. **Stage 1, no model.** Facts from the ledger, heal records, the issue worktree's git diff and the session transcripts, and six rules: a fixer changed files outside the module of the implementer's change, fixer attempts that changed nothing, a turn that re-wrote context on resume, a second implementer session (a restart), transcripts holding more tokens than the records, and a `healed=-` RESULT line although a fixer fixed something.
2. **Stage 2, one cheap judge call** (about $0.07, capped at $0.30): a free-text note about the run (your open-coding input, section 8) and at most two proposals nothing else covers. Often it proposes nothing.
Two more rules note method C problems: a run that had no navigation backend, and a run that built an index and never called it. They are only noted, never filed.
3. **Stage 3, filing.** A rule finding comments on the open issue that already describes it, so the epic collects counts; a new proposal is filed under the epic. Nothing is implemented without you marking it Ready.

`YUKTA_LOOP_ANALYZE=dry` shows what it would do; the analysis of a run is `$DEV_HOME/loop/analysis/<draw-id>.json`. Treat the analyzer as a grader whose precision you measure: how many of its proposals you approve.

### 6.2 Reading the loop: sync, report, themes

Run `yk loop sync` after PRs have been reviewed, then `yk loop report`:

1. **`yk loop sync`** adds what happened after each PR (CI, review comments, human commits, merged, lead time) and how the analyzer's filed issues ended (done, closed otherwise, open).
2. **`yk loop report`** shows each method's cost with a bootstrap interval, the size-adjusted cost ratio, what was left out (forced draws, issues that change the loop, other loop commits), the numbers after the PR and the analyzer's precision. **Below 8 runs per method it prints "No verdict"**: take it literally. With about 20 to 30 issues over three methods you will mostly see directions, not results; the paired evaluation (section 5) is where a comparison is trustworthy.
3. **`yk loop themes`** counts the recurring causes: by rule (no model) and by theme (one cheap judge call over the analyzer's notes), and `--post` puts the counts on the epic issue that matches. This is the axial-coding step of section 8: rank what to fix by how often it happens and what it costs.

### 6.3 The golden set: replay on a loop change

The random stream compares methods on different issues, so it can only show directions. The golden set is where methods are compared on **the same issues**, repeated: a few frozen issues, each with a grader and a frozen base commit, replayed in every method with several trials, whenever the loop changes in a way that matters.

```bash
eval/golden.sh status                    # the set, this checkout's loop commit, replays so far, the budget, and whether a replay is due
eval/golden.sh validate                  # every grader fails on its frozen base and passes with its reference fix
eval/golden.sh run --dry-run             # the plan: every method once per issue and trial, in a seeded shuffled order
eval/golden.sh run --trials 3            # the replay (serial, isolated clones, nothing touches GitHub)
eval/golden.sh report                    # reliability (pass^k), cost spread, a comparison paired by issue
eval/golden.sh report --loop X --against Y   # does loop commit X cost or deliver differently from Y on the same issues?
```

- **When:** once per major loop change, on a committed checkout (an uncommitted tree is refused: it is not a version). A loop commit that already has a complete replay is refused unless `--force`; a replay that stopped on its budget or was interrupted is resumed by the next `run`.
- **Money:** its own budget, `YUKTA_GOLDEN_BUDGET_TOTAL` ($30), separate from the loop's $60; no run starts at the limit. One issue in two methods with three trials is six runs, roughly $10 to $15 judging by the first evaluation.
- **What it reports:** per method the success rate (code correct, and for B and C the workflow ended `SUCCESS`), **pass^k** (the chance that k trials in a row all succeed), the median cost with a bootstrap interval and the spread of the cost over trials; per issue and method the cells; a **comparison paired by issue** (per issue the ratio of median costs against method A, then the geometric mean over issues with a bootstrap interval), and with `--against` the same between two loop commits.
- **No verdict below five issues** (`--min-issues`): with fewer, the per-issue numbers are shown and no conclusion across issues is drawn. The set has one issue today (#1207), so the cross-issue comparison is not available yet: add issues (`eval/README.md`, "Adding an issue", then add it to `eval/golden/set.json` with its frozen base commit and `golden.sh validate`), preferring issues whose first-run outcomes were mixed.
- Offline test: `eval/golden-selftest.sh`.

## 7. Reading numbers without fooling yourself

| Pitfall | What to do |
|---|---|
| **Small samples.** Three runs per arm show a direction, not a number; 20 to 30 issues over two methods leave 3 to 5 runs per method and size. | Report median and range, never a mean alone. Do not call a difference below 8 runs per method. The planned report prints "no verdict" there. |
| **Different issues are not comparable.** An expensive issue in one arm looks like an expensive method. | Compare within a size stratum, or adjust for size; use the paired evaluation for a real comparison. |
| **Correct is not delivered.** A hidden test pass can hide an `ESCALATE`. | Read the `RESULT` line of every B run. |
| **Records undercount.** A restarted run in the same worktree overwrites the first run's heal and implement records (issue #1250). | Compare `cache_trace.py` totals with the records; add the missing run to `extra.json`. |
| **Cost is not value.** Tokens and dollars say nothing about whether the change is good. | Always read cost next to a quality measure (hidden test, delivered, CI, review). |
| **Check scope differs between arms.** Arm A is told to finish with `yk check --no-heal -m <module>`; arm C's flow chose its own scope (the whole repository at the first evaluation). | Treat it as a finding about the loop, but say so when you compare. |
| **Dollars versus tokens.** `total_cost_usd` is list price; a subscription changes the bill, not the token counts. | Report both; the ratios hold either way. |
| **The loop moves.** A fix to the loop changes what you measure. | Record the loop commit, compare within one, re-run the golden set after a major change. |
| **Self-reports mislead.** In a 2025 randomized trial developers believed AI made them about 20% faster while it made them 19% slower. | Trust the measured numbers; do not substitute impressions. |

Report the cost **next to** quality for each method (a cost versus delivered-quality view); a method that is cheap and often fails is not cheap. Compare against the simple baseline (arm A) first.

## 8. Reading transcripts (error analysis)

Numbers say *that* something is wasteful; transcripts say *why*. For a run you care about:

1. Open the session transcripts under `$CLAUDE_CONFIG_DIR` (default `~/.claude`) `projects/<worktree path with every non-alphanumeric character replaced by '-'>/*.jsonl`. Sub-flow sessions keep transcripts; the harness's main sessions run with `--no-session-persistence` and do not.
2. **Open coding:** for each run write one free-text note per surprising thing ("fixer investigated a failing test it did not cause", "same grep three times"). Do not start from a fixed list.
3. **Axial coding:** after several runs group the notes into named categories and count them. Rank by how often and how much they cost. The categories that recur are the findings worth filing; the first evaluation's came out as an environment leak into tests, an unscoped check, fixers editing files outside the issue, and a cache re-write on resume.
4. Count tool use from the `tool_use` blocks to see what a session relied on.
5. Check that a failure is real and not an unfair grader: treat a 0% pass rate over many trials as a sign of a broken task first.

## 9. Filing what you find

File every finding with evidence, under the epic, so it is tracked and deduplicated:

```bash
yk finding --title "..." --module build --priority P2 --kind perf \
  --workflow implement-issue-no-pauses --execution <id> \
  --evidence "<the lines or numbers that show it>" --repro "<how to see it again>" --expected "<what should happen>"
```

Then attach the new issue to the epic (#1218) through the sub-issues API, which needs the issue's database id, not its number:

```bash
id=$(gh api repos/infenia/yukta/issues/<new number> --jq .id)
gh api -X POST repos/infenia/yukta/issues/1218/sub_issues -F sub_issue_id=$id
```

`scripts/report-finding.sh` comments on an open issue with the same title instead of filing a duplicate. Use `--dry-run` to see the issue first. The issue-triage workflow sets the project fields; set Status last.

## 10. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `eval.sh run B n` ends with `RESULT status=ESCALATE` and tests failing in modules your change does not touch | Known loop defects (the dev server's environment leaking into test JVMs, an unscoped check): see issues #1232 and #1233. Not your change. Read the heal records, then decide whether the defects are fixed on your base commit. |
| The orchestrator ends before `RESULT` | The command timed out. The prompt asks for a 600000 ms Bash timeout and re-attaching with `yk watch <session> <exec>`; check `main.json` `num_turns` and the cap `EVAL_MAIN_TURNS`. |
| `no transcripts for <run>` from `cache_trace.py` | Wrong config dir (set `CLAUDE_CONFIG_DIR`), or the sessions did not persist. Main sessions never persist; sub-flow sessions do. |
| Cost or tokens are much lower than the transcripts suggest | A restarted run overwrote records (#1250). Use the transcripts and `extra.json`. |
| `yk` says no dev server, or measures the wrong code | `yk up`; `yk status` shows the commit it was built from. For a branch, set `YUKTA_DEV_HOME`, `YUKTA_DEV_PORT`, `YUKTA_DEV_MAIN_REF` in the shell that runs the harness. |
| `yk pick` is slow before the draw | The Ready check reads the whole project board (`gh project item-list ... --limit 700`), which can take a minute or more. |
| Hidden test fails to compile | A solver renamed the function the test calls. Flag the run for manual review; do not score it as a failure. |
| `eval.sh run` removed my earlier run | It rebuilds `runs/<arm><n>`. Use a new repetition number, or copy the folder first. |
| `selftest.sh` fails after you changed `eval.sh` | The self-test checks run directories, stubs, usage collection and grading with a fake `claude`; read which assertion failed. |
| A run spent more than expected | Per-session caps are `EVAL_BUDGET` and `YUKTA_BUDGET_ISSUE`; the loop's total is `YUKTA_LOOP_BUDGET_TOTAL`. Lower them before the next run. |

## 11. Safety and cost control

- The paired evaluation never touches GitHub: `gh` is a stub that serves the saved issue and logs writes. `yk pick` is the only path that claims an issue and opens a draft PR, and it only takes issues assigned to you and Ready.
- Main sessions refuse `git commit`, `git push` and `gh`; the workflow commits and opens the PR.
- Third-party tools (a code index for arm C) are installed from a pinned release with a verified checksum, configured per run, and never through an install script that edits your Claude config.
- Start with `--dry-run`, `selftest.sh` and one run before you spend on a set.
- Everything is capped: per session, per run and in total. A cap that stops a run is a result worth reading, not a nuisance.

## 12. Where the first evaluation stands

Issue #1207, three runs per arm, base `main@4349e6247`: arm A (Claude fixes alone) median about $0.40; arm B (orchestrated) median $2.15 on the recorded costs and about $3.8 once the lost first run of B2 is counted. All six code fixes were correct; B delivered 2 of 3 times. About 81% of arm B's spend went to fixer attempts on failures outside the issue's module. The findings (#1232 to #1235, #1248 to #1251, #1256 to #1261) are filed under the epic #1218. The full report is `eval/results/2026-10-25-1207/report.html`.

## 13. Glossary

- **Arm / method**: one way of doing the work (A or C; B is retired, see Methods).
- **Stratum**: a size group (S, M, L) inside which methods are balanced.
- **Block randomization**: draws in blocks that hold each method once, so counts stay balanced.
- **Ledger**: the append-only record of draws and outcomes.
- **Paired**: the same issue given to each method.
- **Hidden test**: a test added after a run, never shown to Claude.
- **pass^k**: the chance that all k repeats of a task succeed (reliability); **pass@k**: at least one succeeds.
- **Open coding / axial coding**: free-text notes on traces first, then grouping them into counted categories.
- **GQM**: Goal-Question-Metric, the rule that every metric answers a question that serves a goal.
- **Loop commit**: the commit of the dogfood scripts a run used.

## 14. Background reading

- METR randomized trial of AI coding tools; its 2026 update on why the design broke
- "AI Agents That Matter" (Princeton): cost-controlled evaluation
- Anthropic, "Demystifying evals for AI agents": tasks, trials, graders, transcripts
- "Adding Error Bars to Evals": paired differences and power
- "Efficient Benchmarking of AI Agents": mid-difficulty task selection
- Crossover designs in software engineering experiments
- Basili et al., the Goal-Question-Metric approach
- OpenTelemetry GenAI semantic conventions (token usage attribute names)

The links are listed at the end of `design/plans/dogfood-improvement-loop.md` (added with PR #1266).
