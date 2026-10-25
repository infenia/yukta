# Yukta loop runbook: how to run it, and how to read what it tells you

Keep this open while you run. It is the operator's manual for the improvement loop and the evaluations. The shorter guides are [EVALUATION.md](EVALUATION.md) (the why and the method), [README.md](README.md) (the commands of `yk`) and [eval/README.md](eval/README.md) (the harness files). Everything here is a command you can paste.

## 0. Read this first

**Where to run it from.** Until the pull requests #1252, #1266 to #1271 are merged, the commands exist only in the branch `feat/dogfood-golden-set` (it contains all of them). Use that checkout: `cd /media/arun/Infenia/Infenia/Development/Public/yukta-loop/examples/11-dogfood` (a worktree of this repository). After the merge, use any up-to-date checkout of `main`. All paths below are relative to `examples/11-dogfood` unless they start with `yk` (the `yk` script in that directory; in Claude Code it is on your PATH).

**What has and has not been proven.** The pieces are tested offline with fakes, against the real dev server with a fake `claude` and `gh`, and with a few real model calls (the analyzer, the themes judge, one headless session using the navigation index). **No real issue has gone through `yk pick`, and no real golden replay has run.** Your first real run is therefore also the first end-to-end test. Section 4 walks you through it carefully and cheaply.

**What touches GitHub.**

| Command | Reads GitHub | Writes to GitHub |
|---|---|---|
| `eval.sh`, `golden.sh` (the paired evaluation and the golden set) | no (a stub serves the issue) | **never** |
| `yk run issue <n>` (any variant) and `yk pick <n>` | the issue and the project board | moves the issue to In progress, pushes the branch `issue/<n>`, opens a **draft PR**; gives the issue back to Ready on failure |
| the analyzer (`yk pick` runs it; `yk analyze`) | the issue and the epic's open issues | comments on known issues, **files new issues under epic #1218** (unless `YUKTA_LOOP_ANALYZE=dry` or `off`) |
| `yk loop themes --post` | the epic's issues | comments counts on a matching epic issue |
| `yk loop sync`, `yk loop report`, `yk loop total/ledger/plan` | PRs and issues (sync only) | nothing |

**What costs money.** Claude sessions: the main session and, for methods B and C, the sub-flow sessions (implementer, fixers), plus the analyzer's judge call (about $0.07) and the themes judge call (about $0.07). Section 8 has the numbers.

## 1. The picture in one page

**Three ways to evaluate.**

1. **The random stream (`yk pick`).** You work through your real issues one by one. For each, a method is drawn at random (balanced by issue size), the run is recorded in a **ledger**, and an **analyzer** reads it. This is the day-to-day mode; it produces real pull requests and finds waste inside the loop. It compares methods only on *different* issues, so it shows directions, not results.
2. **The paired evaluation (`eval/eval.sh`).** One issue, run in several methods on throw-away clones with a stubbed GitHub. Use it for a controlled look at one issue, or to find root causes. Nothing touches GitHub.
3. **The golden set (`eval/golden.sh`).** A few frozen issues, each replayed in every method with several trials, once per major loop change. This is the only place methods are compared on **the same** issues, repeated; also where a change to the loop itself is checked.

**The three methods.**

| Method | Who does the work | What Yukta does |
|---|---|---|
| **A** | One Claude Code session (your own setup) fixes the issue itself | only checks (`yk gradle`, `yk check --no-heal`), no healing |
| **B** | A Claude Code session is an orchestrator that starts `yk run issue` | a slim implementer, the check, and fixer sessions that resume the implementer |
| **C** | like B | like B plus a code-navigation index for the implementer and fixers (IDE index if you opted in, else codebase-memory-mcp, else CodeGraph) |

**Where the triage heal applies.** A plain `yk check` (what your own session and the Stop hook run) heals through `heal`: a System One triage picks the model and effort, `fix` climbs effort, and a failing model hands over to the next ([README.md](README.md), "Self-healing check"). None of the three methods uses it: A runs `--no-heal`, and B and C heal inside `check-impl` with `resume` rungs, so their costs are not affected. Heal records of a plain check carry `effort`.

**How data flows (continuous loop).**

```
yk next                  -> your assigned, Ready issues, with size S/M/L
yk pick <n>              -> eligible? -> size -> DRAW (written to the ledger first) -> run the method -> OUTCOME (cost, tokens, result, PR)
                            -> analyzer: facts + rules -> one cheap judge call -> comments / new issues under the epic
yk loop sync             -> after the PR: CI, review comments, human commits, merged; how the analyzer's issues ended
yk loop report           -> per method: cost [interval], delivered; a size-adjusted comparison; "no verdict" under 8 runs
yk loop themes           -> what keeps going wrong, counted (rules without a model, notes grouped by one judge call)
```

**Where things live** (all under `$DEV_HOME` = `~/.local/state/yukta-dev` unless noted):

| What | Where |
|---|---|
| the ledger (draws, outcomes, syncs, analyses) | `$DEV_HOME/loop/ledger.jsonl` |
| a run's main session output | `$DEV_HOME/loop/runs/<draw-id>/` |
| a run's analysis | `$DEV_HOME/loop/analysis/<draw-id>.json` |
| themes output and what was posted | `$DEV_HOME/loop/themes/` |
| the issue's worktree and records (method B and C) | `../yukta-issue-<n>/` and `<checkout>/.yukta-dev/issue-<n>/` |
| the paired evaluation's runs | `<checkout>/.yukta-dev/eval/runs/<arm><n>/` |
| the golden set's ledger and runs | `<checkout>/.yukta-dev/golden/` |
| codebase-memory-mcp's indexes (method C) | `$DEV_HOME/cbm/` (about 120 MB per worktree; safe to delete) |
| Claude session transcripts | `$CLAUDE_CONFIG_DIR` (default `~/.claude`) `/projects/<path with every non-alphanumeric replaced by '-'>/` |

## 2. Before you start: the pre-flight checklist

Run these once. Each line says what a good answer looks like.

```bash
cd /media/arun/Infenia/Infenia/Development/Public/yukta-loop/examples/11-dogfood   # the checkout that has all the loop commands
git branch --show-current                    # feat/dogfood-golden-set (or main once merged)
claude --version                             # claude is installed and logged in
gh auth status                               # logged in; the token needs the project scope:
gh auth refresh -s project                   #   run this if the issue claim later says it cannot move the project item
jq --version; python3 --version; go version  # all present (go: the cli module's grader and tests)
./yk status                                  # "up: http://127.0.0.1:9858 main@<commit> current"; if down: ./yk up
./yk loop total                              # $0 of $60 on a fresh ledger
./yk next                                    # lists your assigned, Ready issues; empty means none is Ready: see below
scripts/nav.sh probe                         # which navigation backends could run (method C): see section 3.2
```

- **An issue must be assigned to you and Ready** on the Yukta Roadmap project (project 5) for `yk pick`. `yk next` shows the eligible ones, by priority, with their size. An issue that is not Ready has no acceptance criteria yet: fix that on the board, not in the loop.
- **The dev server must be built from code that has what you test.** `yk up` builds `origin/main`. The loop's workflows come from your checkout's scripts (applied to the server for each run), so a main-built server is enough for `yk pick`. To test unmerged *engine* changes use a branch server (section 3.3).
- **Run the free self-tests once** (no server, no spend) to know the tooling works on your machine:

```bash
eval/selftest.sh && eval/golden-selftest.sh
scripts/test-loop.sh && scripts/test-analyze.sh && scripts/test-nav.sh && scripts/test-loop-report.sh && scripts/test-themes.sh
```

## 3. The ways of running

### 3.1 Directly, with no recording: `yk run issue`

For one issue, no draw, no ledger, no analyzer. Useful to try a method by hand or to reproduce something.

```bash
yk run issue 1234                                   # method B with both checkpoints (you approve before the work and before the PR)
yk run issue 1234 --no-pause before-implement,before-pr   # unattended
yk run issue 1234 --solo --no-pause before-pr       # method A
yk run issue 1234 --nav  --no-pause before-implement,before-pr   # method C
yk run issue 1234 --detach                          # start and return; follow with: yk watch <session> <exec>, yk tree <exec>
yk signals; yk approve <exec> before-pr             # answer a checkpoint (or: yk reject <exec> before-pr)
```

Each opens a **draft PR** from `issue/<n>` on success and gives the issue back to Ready on failure (the worktree `../yukta-issue-<n>` stays). Method details: [README.md](README.md) ("Issues", "Method A", "Method C").

### 3.2 The continuous loop: `yk next`, `yk pick`

The main mode. Always start with `--dry-run`.

```bash
yk next                          # eligible issues by priority with size
yk pick 1234 --dry-run           # shows size, loop-changing flag, drawn method; writes NOTHING (no draw is used up)
yk pick 1234                     # draws (ledger first), runs the method, records the outcome, then analyzes it
yk pick 1234 --method A          # force a method: recorded as forced and LEFT OUT of every method comparison
yk loop plan 1234 --dry-run      # the draw alone
yk loop ledger                   # one line per draw and outcome
yk loop total                    # dollars spent of the $60 budget
```

What `yk pick` does, in order, and what to expect on screen:

1. Checks the budget (refuses at $60: that is your review point) and that the issue is open, assigned to you and Ready. The Ready check reads the whole project board and can take a minute or more.
2. Computes the size (S, M, L) and whether the issue changes the loop itself, then **draws the method** (seeded blocks inside the size, so methods stay balanced) and prints the draw as JSON. A second `pick` of the same issue keeps its method.
3. Runs the method in the foreground. **Method A** takes minutes. **Methods B and C** take about 8 to 17 minutes (the orchestrator waits in the foreground). Run it in a terminal you can leave alone, for example `tmux`.
4. Records the outcome (cost and tokens of the main session and the sub-flows, wall time, the RESULT line, the PR URL; for C the backend and index time).
5. Runs the analyzer (section 5.2) unless `YUKTA_LOOP_ANALYZE=off`.

**Knobs for the stream** (set them in the shell before `yk pick`):

| Variable | Default | Effect |
|---|---|---|
| `YUKTA_LOOP_METHODS` | `A,B,C` | the pool the draw uses; `A,B` leaves C out; one letter makes the draw trivial |
| `YUKTA_LOOP_SEED` | a random seed kept in `$DEV_HOME/loop/seed` | fix it to reproduce a draw sequence |
| `YUKTA_LOOP_ANALYZE` | `file` | `file`: the analyzer comments and files issues; `dry`: shows what it would do; `off`: skips it |
| `YUKTA_LOOP_BUDGET_TOTAL` | 60 | USD; `yk pick` refuses at the limit |
| `YUKTA_LOOP_BUDGET_SESSION` | 5 | USD cap per Claude session |
| `YUKTA_LOOP_MODEL` | `opus` | the orchestrator's model in B and C |
| `YUKTA_ANALYZER_MODEL`, `_BUDGET`, `_MAX_FILED` | sonnet, 0.30, 3 | the judge model, its cap per call, the most new issues per run |
| `YUKTA_NAV_BACKENDS` | `ide,cbm,codegraph` | method C's order of backends |
| `YUKTA_NAV_IDE` | unset | `open` lets the IDE open each worktree as a project (a window and a fresh index per issue); the IDE backend is **skipped otherwise** |

**Method C backends (what `scripts/nav.sh probe` shows).**

| Backend | Used when | Index cost here |
|---|---|---|
| `ide` | `YUKTA_NAV_IDE=open` and the IDE's index server answers | the IDE's own; **not tested against a real IDE by the loop's tests** (a stub only): try it once yourself and read `nav.json` |
| `cbm` | `codebase-memory-mcp` is on your PATH (version recorded, not pinned) | about 13 seconds |
| `codegraph` | `codegraph` is installed | about 8.5 minutes |

If no backend works the run goes on as B and the outcome says `backend: none` (the analyzer notes it): treat such a run as a B run.

### 3.3 The paired evaluation: `eval/eval.sh`

One issue, several methods, throw-away clones, a stubbed GitHub. Full details: [EVALUATION.md](EVALUATION.md) section 5. The short version:

```bash
cd eval
./selftest.sh                                       # free check of the harness
export EVAL_ISSUE=1234                              # default 1207
export EVAL_BASE=$(git rev-parse origin/main)       # pin ONE base commit for every run
./eval.sh validate                                  # the grader must fail on the base and pass with the reference fix (needs issues/1234/ set up: see below)
./eval.sh run A 1; ./eval.sh run B 1; ./eval.sh run C 1      # alternate the arms: A1 B1 C1 A2 B2 C2 ...
./eval.sh report                                    # per run and per arm tables
./split.sh                                          # where arm B/C's sub-flow money went
python3 -I cache_trace.py B1 --summary              # turns that re-wrote the cache, per session
```

- **Set up an issue** (once): `./eval.sh fixture 1234` saves the issue; write `issues/1234/grade.env` and the hidden test; keep a `reference-fix.patch`; run `./eval.sh validate`. See [eval/README.md](eval/README.md) ("Adding an issue").
- **Branch server** (to test unmerged engine or `yk` changes): `export YUKTA_DEV_HOME=$HOME/.local/state/yukta-dev-mybranch YUKTA_DEV_PORT=9866 YUKTA_DEV_MAIN_REF=my-branch; yk up`, run the evaluation in the *same shell*, then `yk down`.
- Runs are **serial**, alternating arms; `eval.sh run X n` deletes and rebuilds `runs/Xn`; use a new number to keep an old run.
- If a run dies, `./eval.sh collect X n` rebuilds its `result.json` from the files; `./eval.sh grade X n` only re-grades.

### 3.4 The golden set: `eval/golden.sh`

Frozen issues, every method, several trials, **once per major loop change**.

```bash
cd eval
./golden.sh status                          # the set, this checkout's loop commit, replays so far, the budget, "Due" if no complete replay
./golden.sh validate                        # every grader fails on its frozen base and passes with its reference fix (free)
./golden.sh run --dry-run                   # the plan: each method once per issue and trial, in a seeded shuffled order
./golden.sh run --trials 3                  # the replay (serial; nothing touches GitHub); stops at the $30 budget, the next `run` resumes it
./golden.sh report                          # reliability (pass^k), cost spread, paired comparison
./golden.sh report --loop X --against Y     # does loop commit X cost or deliver differently from Y on the same issues?
```

Rules it enforces: run it from a **committed** checkout (a dirty tree is refused: it is not a version); a loop commit with a complete replay is refused unless `--force`; `--methods A,C` and `--issues 1207` narrow a replay; `--trials` must be at least 2 for pass^k to mean anything. Money: its own `YUKTA_GOLDEN_BUDGET_TOTAL` ($30). **The set has one issue (#1207)**, so the cross-issue comparison prints "No verdict" until you add at least five (section 5.7).

### 3.5 Analysis only (no new runs, no money except the judge)

```bash
yk analyze <draw-id> --dry-run              # re-read one finished run; --no-judge skips the model call
yk loop sync                                # CI, review comments, human commits, merged, and the state of issues the analyzer filed
yk loop report                              # the comparison
yk loop report --loop 8b3210f4a             # within one loop commit
yk loop themes --no-judge                   # rule counts only (free)
yk loop themes --post                       # + one judge call, and comment the counts on the matching epic issues
eval/eval.sh collect B 1                    # rebuild a paired run's result
python3 -I eval/cache_trace.py --worktree ../yukta-issue-1234 --json   # cache trace of an issue worktree
```

## 4. Walkthrough: your first real run

The aim: prove the plumbing end to end, cheaply, **before** you let the random stream decide anything. Use forced draws (`--method`): they are recorded, cost-counted, and left out of every comparison, so they cannot contaminate the results.

**Step 0: choose the issue.** `yk next`. Pick a **small (S)** issue that is *not* about the loop itself (the loop-changing flag would exclude it anyway). Read its acceptance criteria; you will review the PR.

**Step 1: switch the analyzer to look-only.** `export YUKTA_LOOP_ANALYZE=dry` (so it prints what it would file instead of filing it).

**Step 2: dry-run the pick.** `yk pick <n> --dry-run`. Check: the size is what you expect; `loop_changing` is false; a method is drawn; nothing was written (`yk loop ledger` is still empty).

**Step 3: run method A first (cheapest, about $0.4, a few minutes).**

```bash
yk pick <n> --method A
```

Watch for: the draw JSON; the workflow output; a final `RESULT status=SUCCESS` line; a draft PR URL; the `ANALYSIS` line from the analyzer (dry). Then:

```bash
yk loop ledger                                   # a forced draw and an outcome
jq . ~/.local/state/yukta-dev/loop/analysis/<draw-id>.json | less   # facts, rule findings, the judge's note
```

Open the PR and review it like any PR. If something is off, fix it in the loop (a dogfood issue), not around it.

**Step 4: run a B on another small issue** (about $2 to $4, 8 to 17 minutes): `yk pick <m> --method B`. Compare the two outcomes' costs in `yk loop ledger`, and read the analyzer's note: it names what was wasteful.

**Step 5: look at the report.** `yk loop sync` (after the PRs are reviewed or merged), then `yk loop report`. With two forced runs it will say there is nothing in the comparison set yet: that is correct.

**Step 6: switch the analyzer on.** `unset YUKTA_LOOP_ANALYZE` (default `file`). From now on it files issues under epic #1218; **you approve by marking an issue Ready**; nothing is implemented without that.

**Step 7: start the stream.** `yk pick <n>` with no `--method`. One issue at a time. After every five or so runs: `yk loop sync && yk loop report && yk loop themes`.

**Step 8: the review at $60.** `yk pick` refuses at the budget. Read `yk loop report` and `yk loop themes`, decide what to fix, whether to continue (raise `YUKTA_LOOP_BUDGET_TOTAL`), and whether to drop a method that is clearly dominated.

## 5. Analyzing: how to read every output

### 5.1 What `yk pick` prints

The draw JSON, then the workflow's last lines (the `RESULT` line, the PR URL), then the analyzer's lines. In the draw record: `size` (S/M/L and where it came from), `loop_changing` (excluded from comparisons if true), `forced`, `block`/`position` (where in the seeded block), `loop_sha` (the version of the dogfood scripts used; compare runs only within one), `base_sha`.

`RESULT status=<S> failed_node=<node|-> healed=<stage:step,..|-> execution=<id> session=<id>`: `SUCCESS` means a branch ready for a draft PR. `ESCALATE` means the run did not finish: the issue is Ready again and the worktree stays. **`healed=-` can be wrong** (issue #1235): trust the heal records and the analyzer's `healed-field-mismatch` rule.

### 5.2 The analyzer: facts, rules, note, proposals

For each run `$DEV_HOME/loop/analysis/<draw-id>.json` holds `facts`, `findings` (rules that fired), `note` (the judge's open-coding note), `proposals`, `filed`, `analyzer_cost_usd`. The `ANALYSIS` line summarizes it.

| Rule | It means | What you do |
|---|---|---|
| `fixer-outside-module` | a fixer changed files outside the module of the implementer's change; they are in the PR | read the PR diff for unrelated edits; known issue #1234 |
| `noop-fixers` | fixer attempts that changed nothing but cost money | look at the cost line; known issues #1256, #1249 |
| `cache-rewrite-on-resume` | a turn re-wrote context the cache should have served (the first fixer after the implementer) | tokens, not much money; known issue #1248 |
| `restart-overwrites-records` | two implementer sessions in one worktree: the run was started again, the earlier records were overwritten | the cost on record is too low; known issues #1250, #1257 |
| `records-undercount` | the transcripts hold more cache tokens than the records | same: trust the transcripts (`eval/cache_trace.py`) |
| `healed-field-mismatch` | the RESULT line says `healed=-` but a fixer fixed something | known issue #1235 |
| `nav-backend-none` | method C ran with no backend: it was a B run | exclude it from C when you analyze by hand |
| `nav-unused` | the index was built (and its time spent) but the implementer never called it | cost of C without benefit: look at the rules the implementer got |

A finding for a known issue becomes a **comment** on that issue (so the epic collects counts); a closed known issue is filed again. The judge may add up to two **proposals** the rules and the open issues do not cover: it often adds none, which is the right answer. The note is the part to read: it is the analyzer's plain-language account of the run with numbers.

**Judging the analyzer.** Precision = of the issues it filed, how many you took (`yk loop report` shows it after `yk loop sync`). If you reject most proposals, set `YUKTA_LOOP_ANALYZE=dry` and tighten the rules instead of tolerating noise.

### 5.3 `yk loop report`, section by section

Run `yk loop sync` first so the after-PR sections have data.

| Section | How to read it |
|---|---|
| **Header** | finished runs, how many are in the comparison, what was left out and why, money spent. "spans N loop commits" means the runs used different versions of the loop: use `--loop <sha>` |
| **Runs** | one row per run: issue, size, method, nav backend and index seconds, loop commit, cost, tokens, wall, result, CI, review comments, flags (`forced`, `loop-changing`, `restarted`). A restarted run counts once with its costs added up |
| **Per method** | `n`, delivered (RESULT SUCCESS), median cost with a 90% bootstrap interval, tokens, wall. `(n<8)` marks a method with too few runs |
| **Per method and size** | median cost per cell with `n`: check that every method has runs in every size before believing a comparison |
| **Comparing the methods** | with every method at 8 or more runs: a **cost ratio per method against the baseline** (A, else the first method) from a regression of log(cost) on method and size, with a 90% bootstrap interval; "clear" = the interval excludes x1.00 (not a p-value). Below 8: **"No verdict"**: do not conclude. The Pareto line (no other method is cheaper and delivers at least as often) appears only when every method has enough runs |
| **Left out** | forced draws and issues that change the loop: they are analyzed but never compared |
| **After the pull request** | CI passed, median review comments, median human commits (commits without the Claude trailer: your fixes after the agent), merged. This is the quality side; cost without it means nothing |
| **The analyzer** | comments, issues filed and how they ended (done / closed otherwise / open), precision of the decided ones |

**Rules of thumb.** (1) Never read cost without delivered and the after-PR numbers beside it: a cheap method that often fails is not cheap. (2) With 20 to 30 issues over three methods expect about 7 to 10 runs per method and 2 to 4 per size: the report will mostly say "no verdict". That is the report being honest. For a real comparison use the golden set or a paired evaluation. (3) Compare within one loop commit.

### 5.4 `yk loop themes`

Two tables. **Rules (no model):** for each analyzer rule, the analyzed runs it fired in, what those runs cost, and the open issues that describe it: sort by runs then cost to decide what to fix first. **Themes (one judge call, about $0.07):** the notes grouped into at most six causes; the counts and dollars are computed from the analyses (the model only groups run ids; an invented id counts for nothing). Needs at least 3 notes. `--post` comments the counts on the matching open epic issue, once per change. Use it after every handful of runs; the big, repeated, expensive themes are the loop's next improvements.

### 5.5 `yk loop sync`

Reads, for every run with a PR: CI (`success`, `failure`, `pending`, `none`), review comments (reviews + comments + inline), human commits, merged, lead time; and for every issue the analyzer filed: closed as done, closed otherwise, open. A merged or closed PR is not read again. Run it whenever PRs have moved.

### 5.6 The paired evaluation's outputs

| Command | What it shows |
|---|---|
| `eval.sh report` | per run and per arm: cost, tokens (main vs sub-flows), cache-read share, turns, attempts, wall, **hidden test and suite pass** (the code is right) |
| `result.json` → `answer` | the end of the main session's reply with the workflow's `RESULT` line: **count only `status=SUCCESS` as delivered**. A run can hold correct code and still end ESCALATE |
| `split.sh` | arm B/C's money by where it went: implementer, fixers that touched the issue's module, fixers elsewhere. A big "elsewhere" means the check ran into failures the change did not cause |
| `cache_trace.py B1 --summary` | turns that re-wrote context; `--worktree <path> --json` for tools used per session |
| records vs transcripts | the heal and implement records are overwritten by a restart: if `cache_trace.py` totals exceed the records, add the missing run to `extra.json` |

Interpreting a paired evaluation: compare the arms **per run**, read the median and the range, and look at *why* with the transcripts and the analyzer rules before drawing a conclusion. Three runs per arm show a gap, not a precise number.

### 5.7 The golden set's report

`golden.sh report` shows: **per method** the success rate (code correct and, for B and C, the workflow SUCCESS), **pass^k** (the chance that k trials in a row all succeed: `1.00 / 0.67 / 0.33` means one trial succeeds, two in a row succeed two thirds of the time, and so on; reliability falls with k), median cost with an interval, and the **spread of cost across trials** (CV). **Per issue and method** the cells with the backend. **A comparison paired by issue**: per issue the ratio of median costs against A, then a geometric mean over issues with a bootstrap interval, **only with at least five issues** (`--min-issues`). `--against Y` does the same between two loop commits: this is how you check that a change to the loop did not make things dearer or less reliable.

To grow the set: set up the issue's folder (section 3.3), give it a frozen base commit and an entry in `eval/golden/set.json`, run `./golden.sh validate`, and prefer issues whose first-run outcomes were **mixed** (neither always solved nor never).

### 5.8 What to do with the results

| You see | Do |
|---|---|
| a rule fires in most runs and a known issue exists | fix the issue (it is already counted on the epic); then re-run the golden set to see the effect |
| the analyzer's note names a waste no rule knows | read the transcripts (`eval/cache_trace.py`, the session folder); if real, file a finding (`yk finding ...`) |
| method X costs clearly more at equal delivery, with n at least 8 each | stop drawing it (`YUKTA_LOOP_METHODS`), keep the golden set as the check |
| "No verdict" | keep running the stream, or use the golden set; do not decide yet |
| C shows `nav-unused` or `backend: none` often | the index is not helping or not available: check `scripts/nav.sh probe`, the implementer's rules, and the backend's index time |
| the budget is reached | the review point: read the report and themes, then decide |

## 6. Settings reference

**Loop:** `YUKTA_LOOP_METHODS`, `YUKTA_LOOP_SEED`, `YUKTA_LOOP_DIR`, `YUKTA_LOOP_BUDGET_TOTAL`, `YUKTA_LOOP_BUDGET_SESSION`, `YUKTA_LOOP_ANALYZE`, `YUKTA_LOOP_MODEL`, `YUKTA_LOOP_EPIC`, `YUKTA_LOOP_RUNNER` (tests).

**Analyzer / themes:** `YUKTA_ANALYZER_MODEL` (sonnet), `YUKTA_ANALYZER_BUDGET` (0.30), `YUKTA_ANALYZER_MAX_FILED` (3).

**Method A:** `YUKTA_SOLO_MODEL` (opus), `YUKTA_SOLO_BUDGET` (5), `YUKTA_SOLO_TURNS` (60). **Methods B and C (sub-flows):** `YUKTA_IMPL_MODEL` (opus), `YUKTA_IMPL_BUDGET` (5), `YUKTA_BUDGET_ISSUE` (per issue, optional).

**Method C:** `YUKTA_NAV_BACKENDS`, `YUKTA_NAV_IDE` (`open`), `YUKTA_NAV_IDE_URL`, `YUKTA_NAV_CBM_BIN`, `YUKTA_NAV_CBM_CACHE`, `YUKTA_NAV_INDEX_TIMEOUT` (1500 s).

**Paired evaluation:** `EVAL_ISSUE`, `EVAL_BASE`, `EVAL_DIR`, `EVAL_SRC`, `EVAL_YK`, `EVAL_MODEL`, `EVAL_BUDGET`, `EVAL_MAIN_TURNS`, `EVAL_CLAUDE`, `EVAL_ISSUES_DIR`.

**Golden set:** `YUKTA_GOLDEN_BUDGET_TOTAL` (30), `YUKTA_GOLDEN_DIR`, `YUKTA_GOLDEN_SET`, `YUKTA_GOLDEN_SHA` (tests).

**Server:** `YUKTA_DEV_HOME`, `YUKTA_DEV_PORT` (9858), `YUKTA_DEV_MAIN_REF` (a branch for a branch server).

## 7. Costs and time (from the first evaluation, issue #1207)

| What | Cost | Time |
|---|---|---|
| a method A run | about $0.35 to $0.60 | 1 to 3 minutes |
| a method B run | about $2 to $4 (it was mostly fixers working on failures outside the issue) | 8 to 17 minutes |
| a method C run | not measured yet: B's cost plus the index time (13 s `cbm`, 8.5 min CodeGraph) | B's time plus the index |
| the analyzer's judge call | about $0.07 | seconds |
| the themes judge call | about $0.07 | seconds |
| one golden replay of one issue, 3 methods x 3 trials | roughly $15 to $25 | roughly 1 to 2 hours (an estimate: serial, about 15 minutes for each B or C run, a few minutes for each A) |
| the loop's budget | $60 total, $5 per session | |
| the golden set's budget | $30 | |

## 8. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `yk pick` says the issue is not Ready or not assigned to you | it is not on the board in Ready, or not assigned: fix it on the project; nothing was drawn |
| `yk pick` pauses before drawing | the Ready check reads the whole project board (a minute or more) |
| the run ends `ESCALATE` with failing tests in modules the change does not touch | known loop issues #1232 and #1233 (the dev server's environment leaks into test JVMs; the check is not scoped to the change): read the heal records, not your change |
| the orchestrator ended before `RESULT` | it timed out; check `$DEV_HOME/loop/runs/<id>/main.json` and `main.err`; re-attach with `yk watch <session> <exec>` |
| the analyzer filed something you did not want | set `YUKTA_LOOP_ANALYZE=dry` or `off`; close the issue; the cap is `YUKTA_ANALYZER_MAX_FILED` |
| `yk loop report` says "No verdict" | n below 8 per method: expected; see 5.3 |
| `golden.sh run` refuses | a dirty tree (commit), or this loop commit already has a complete replay (`--force`), or nothing matches `--issues` |
| a golden replay stopped | the budget: read `golden.sh status`, then `golden.sh run` again to resume |
| method C ran as B | no navigation backend worked: `scripts/nav.sh probe`, and read `nav.json` (`attempts`) in `<checkout>/.yukta-dev/issue-<n>/` |
| the IDE backend is skipped | by design, unless `YUKTA_NAV_IDE=open` |
| costs on record look too low after a restart | a restart overwrites the records (issue #1250): use `eval/cache_trace.py` on the worktree |
| the server measures the wrong code | it is built from another commit: `yk status`; for a branch use a branch server (3.3) |
| `cache_trace.py` finds no transcripts | wrong config dir (`CLAUDE_CONFIG_DIR`), or a main session (they are not persisted; sub-flow sessions and method A are) |
| a hidden test does not compile | a solver renamed what the test calls: review that run by hand, do not score it as a failure |
| the disk fills | codebase-memory-mcp indexes (about 120 MB per worktree under `$DEV_HOME/cbm`) and the old issue worktrees: delete what you no longer need |

## 9. Cheat sheet

```bash
# pre-flight
./yk status; ./yk loop total; ./yk next; scripts/nav.sh probe
# the stream
export YUKTA_LOOP_ANALYZE=dry          # first runs; unset later
./yk pick <n> --dry-run                # always first
./yk pick <n> --method A               # smoke test, excluded from comparisons
./yk pick <n>                          # a real draw
./yk loop ledger; ./yk loop total
# after PRs move
./yk loop sync; ./yk loop report; ./yk loop themes; ./yk loop themes --post
./yk analyze <draw-id> --dry-run
# paired evaluation (cd eval)
./eval.sh validate; ./eval.sh run A 1; ./eval.sh run B 1; ./eval.sh run C 1; ./eval.sh report; ./split.sh
# golden set (cd eval)
./golden.sh status; ./golden.sh validate; ./golden.sh run --dry-run; ./golden.sh run --trials 3; ./golden.sh report
./golden.sh report --loop <new> --against <old>
```
