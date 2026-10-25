# Plan: dogfood improvement loop (random method per issue, analyzer after each task)

## Context
The token evaluation (PR #1252, issue #1207, 3 runs per arm) compared two methods on one issue and found that most of the cost was waste inside the loop itself, not the method. You will now run the open issues of epic #1218 one by one through the dogfood. Each issue gets **one method drawn at random** (not both), and an **analyzer** reads every finished run and proposes improvements to the dogfood or to Yukta, filed under #1218 for your approval. The loop improves itself; every run is tagged with the loop version so results stay interpretable.

Decisions made (rounds 1 to 3):
- Draw balanced by issue size; size = deterministic heuristic score, overridable by a label.
- Pool: **A** (Claude fixes alone, Yukta only checks), **B** (`yk run issue`, orchestrator + sub-flows), **C** (B + code navigation: IDE index MCP if available, else codebase-memory-mcp, CodeGraph last).
- Analyzer runs **after** each task; files issues under #1218 (deduped); you approve by marking Ready; it never implements.
- Issues that change the loop are run and analyzed but **excluded from the method comparison**.
- Budget: $5 per session, **$60 total** (analyzer included), then a review.
- Also requested: research standard evaluation methods and consider them (below). Confirmed after the research: golden-set tier with its own $30 budget, built last; bootstrap intervals + size-adjusted regression with no verdict below n=8; analyzer with open-code notes, periodic themes and tracked precision; ledger with OTel GenAI attribute names, export later.

## What the research says, and what we adopt
| Method (source) | Lesson | Adopt? |
|---|---|---|
| **Goal-Question-Metric** (Basili, NASA SEL; the classic software-measurement standard) | Metrics programmes fail without a goal; derive every metric from a question. | **Yes**: GQM table below drives what the ledger records. |
| **Randomized controlled trial** (METR 2025, 16 developers, 246 tasks: AI made them 19% slower while they believed +20%) | Measure, do not ask; self-reports mislead. Their 2026 follow-up broke because subjects changed behaviour. | **Yes**: measured tokens, cost, outcomes only. Loop changes are our "subjects changing", so tag by loop sha. |
| **Error bars, paired differences, power** (Miller, "Adding Error Bars to Evals") | Report standard errors; pair on the same task; small gaps need many tasks (500 questions at 50% needed about a 9-point gap). | **Yes**: bootstrap intervals, no verdict below n=8 per method; paired comparison only on the golden set. |
| **Covariate adjustment / CUPED** (A/B platforms) | Removing pre-treatment variance (here: issue size) tightens estimates; can occasionally look worse on one experiment. | **Yes, simply**: regress log(cost) on method + size score; keep stratified draw. |
| **Crossover / Latin square designs** (SE experiment practice; 2024 review of 67 SE crossover studies: carryover modelled in about 3%) | Each unit gets several treatments, controls task heterogeneity, but carryover (shared state) threatens validity. | **Yes, as a separate small tier**: replay a frozen golden set of issues in all three methods on each loop version, on isolated clones (our eval harness already isolates). |
| **Cost-controlled Pareto reporting** ("AI Agents That Matter", Princeton; HAL: 21,730 rollouts, about $40k) | Never report accuracy alone; plot quality against cost; compare against a simple baseline. | **Yes**: report cost versus delivered-quality per method; A is the simple baseline. |
| **Agent eval structure** (Anthropic, "Demystifying evals for AI agents"): tasks, trials, graders (code, model, human), transcripts; grade the outcome not the path; capability vs regression suites; 20 to 50 tasks from real failures; read transcripts | Non-determinism needs repeated trials; model graders need calibration against humans. | **Yes**: golden set = real failures (#1207 and similar) with code graders; the analyzer is a model grader whose **precision is measured** by your approve/reject rate. |
| **pass^k reliability** (tau-bench): chance all k trials succeed | Reliability drops fast with k (about 25% at k=8 in their retail domain). | **Golden set only** (k=3 repeats are affordable there, not in production). |
| **Mid-difficulty task selection** ("Efficient Benchmarking of AI Agents", 2026): keep tasks with 30 to 70% historical pass rate; 44 to 70% fewer tasks, rank order preserved | Rankings survive a smaller, well-chosen task set; absolute scores do not. | **Yes** for the golden set: pick issues whose first-run outcomes were mixed; compare ranks, not absolutes. |
| **Error analysis: open coding then axial coding** (Husain and Shankar; Arize Phoenix skill) | Read traces, write free-text notes first, cluster into counted failure categories after; a fixed taxonomy goes stale. | **Yes**: the analyzer writes an open-code note per run; a periodic `yk loop themes` clusters them (axial) into counted themes that rank proposals by frequency x cost. |
| **OpenTelemetry GenAI conventions** (`gen_ai.usage.input_tokens`, `output_tokens`, cache read and write counts, `invoke_agent` spans; status: Development) | Vendor-neutral names make traces portable to any backend. | **Names only**: the ledger uses these attribute names; optional OTLP export later. |
| **DORA / SPACE / DX Core 4** | Team-level; output counts (PRs, lines) rise while delivery and stability may not (Faros data in the sources). | **As guardrails per PR**: lead time (claim to merge), CI failure, rework (human commits after the PR), review comments. Not as headline. |
| **Bandits / Thompson sampling** (Test & Roll) | Worth it when learning is costly and decisions sequential, but complex, biases estimates, and a fixed design was competitive. | **Not yet**: fixed balanced draw until the first $60 review; then drop Pareto-dominated methods. |
| PDCA / Kaizen, blameless postmortem | General improvement loop shape. | The loop is PDCA: run, analyze, file, you approve, fix, re-run. |

## Measurement frame (GQM)
- **Goal**: lower the tokens and dollars per *delivered, correct* change through the dogfood, without losing quality or speed, and find waste in the loop itself.
- **Q1** What does a delivered change cost, by method and size? → cost, tokens by category, main versus sub-flow, turns, wall.
- **Q2** Where is the waste inside a run? → fixer attempts outside the issue's module, cache re-writes, restarts, no-change attempts, tool use (stage 1 rules).
- **Q3** Is the output good? → delivered (RESULT), CI, review comments, human commits before merge, merged, change failure.
- **Q4** Does a loop change help? → same metrics grouped by loop sha; golden-set replay per sha.
- **Q5** Is the analyzer useful? → proposals approved / filed, duplicate rate, cost per run.

## Design

### 1. Launcher: `yk pick <n>` / `yk next`
`examples/11-dogfood/yk` + new `scripts/loop.sh`:
1. **Eligible**: open, assigned to you, project Status Ready (reuse the claim query and `set_status` of `scripts/yukta-issue.sh`).
2. **Size**: `scripts/issue-size.sh` (acceptance-criteria bullets + modules named + proposal length) → S/M/L; `size:S|M|L` label overrides.
3. **Loop-changing flag** (pre-run): labels `dogfood` + module `build`/`cli`, or paths under `examples/11-dogfood`, `build-logic` in the body; confirmed after the run from the diff.
4. **Draw**: seeded block randomization inside the size stratum (block = one each of A, B, C), **written to the ledger before the run**.
5. **Run** the method, **analyze**, append the outcome.
6. **Budget gate**: refuse to start at $60 total; print "review".

**Ledger** `$DEV_HOME/loop/ledger.jsonl` (per machine, shared by worktrees). Attribute names follow OTel GenAI where one exists (`gen_ai.usage.input_tokens`, `...output_tokens`, cache read and write counts). Draw record: issue, size, loopChanging, method, nav backend (C), seed, block id, loop sha, base sha, time. Outcome record: cost and tokens by category (main vs sub-flow), turns, attempts, wall, RESULT, PR url; later `yk loop sync` adds CI, review comments, human commits, merged, lead time.

### 2. Methods (all end in the same real claim, worktree, check, draft PR path; no stubs)
- **A**: new workflow `run-issue-solo` = claim, worktree, brief, `solo`, pr, release. `solo` (new step in `scripts/yukta-issue.sh`, modelled on `implement`) is a headless main session as the eval's arm A (`eval.sh main_claude`).
- **B**: today's `run-issue`, launched by a thin orchestrator as in the eval.
- **C**: B plus a `nav` step before `implement` and extra flags in `scripts/_claude.sh` (`YUKTA_NAV_MCP_CONFIG`, matching `--allowedTools`); one constant rules file per backend (`implementer-rules-c-<backend>.md`) so the cached prefix stays identical. Backend chosen at run time and **recorded**; C is compared only within a backend (confound).
  1. IDE index MCP if a probe says it is reachable (open item: confirm the intellij-index plugin works headless with `project_path`).
  2. **codebase-memory-mcp** (DeusData, MIT, no telemetry, headless `codebase-memory-mcp cli index_repository`). Third party, so: **pinned release** into `$DEV_HOME/tools`, SHA-256 from its `checksums.txt` verified, **no install script** (it edits `~/.claude.json` and installs hooks), MCP config passed per run with `--mcp-config`, `CBM_CACHE_DIR=$DEV_HOME/cbm`. Index each worktree as its own project (worktrees are not documented upstream: verify first).
  3. CodeGraph (`codegraph init` in the worktree).
  Indexing time counts toward C's cost.

### 3. Analyzer: `yk analyze <run>` (automatic after each task)
`scripts/analyze.sh` + `analyzer-rules.md`:
1. **Extract (no model)**: ledger row, `eval/cache_trace.py` (re-wrote-context turns), heal/implement records (`eval/split.sh` logic), tool-use counts from transcripts, check scope, files changed outside the issue's module, restart/overwrite detection, `healed=` vs records. Writes `$DEV_HOME/loop/analysis/<run>.json`. Known anti-patterns are code here, not model work.
2. **Judge (one cheap model call, cap $0.30)**: constant slim system prompt; input is the extracted JSON plus the titles of open #1218 sub-issues (so it flags duplicates). Output: an **open-code note** (free text, what went wrong or was wasteful) and proposals `{kind: dogfood|yukta, title, evidence, expected, priority}`.
3. **File**: each proposal through `scripts/report-finding.sh` (exact-title dedupe; duplicates become a comment), labels `analyzer` and `loop:<sha>`, attached under #1218 with the sub-issues API. Drafts for you; nothing is implemented without Ready.
4. **Themes** `yk loop themes` (periodic): cluster the open-code notes into counted failure categories (axial coding) and rank proposals by frequency x cost. Updates the epic's issues with the counts.
5. **Analyzer quality**: precision = approved / filed (from your Ready or close decisions), duplicate rate, cost per run; shown in the report. Every 5th run, a raw-transcript spot check by you or a second model, to catch what the rules and judge miss (recall).

### 4. Statistics and report: `yk loop report`
Per method and size: n, median [bootstrap interval] of cost, tokens, wall; delivered rate; CI pass rate; review comments; cost versus delivered-quality scatter with the Pareto set; log-cost regression on method + size; loop-changing runs listed apart; loop sha per run. **No ranking below n=8 per method** and a printed warning; no p-values at this size. Reuses the jq tables of `eval/eval.sh report`.

### 5. Golden-set replay (second tier, separate from the issue stream)
5 to 8 frozen issues with code graders (start with #1207 and issues with a known reference fix), chosen from mixed first-run outcomes (30 to 70% band). Replay in all three methods, k=3 trials each, on isolated clones with the eval harness, once per **major loop change** (not every issue). Paired comparison, pass^k, cost variance. This is the only place methods are compared on the same task. Budget is its own line (proposed $30), outside the $60 stream.

### 6. Allocation after the first review
Fixed balanced draw until the $60 review. Then drop methods that are Pareto-dominated, or move to Thompson sampling if the stream continues and regret matters.

## Critical files
- Modify: `examples/11-dogfood/yk` (add `pick`, `next`, `analyze`, `loop`), `scripts/yukta-issue.sh` (steps `solo`, `nav`), `scripts/_claude.sh` (nav MCP flags), `implementer-rules.md` (+ per-backend copies), `examples/11-dogfood/README.md`, `eval/README.md`.
- New: `scripts/loop.sh`, `scripts/issue-size.sh`, `scripts/analyze.sh`, `analyzer-rules.md`, `scripts/test-loop.sh`, `eval/golden/` (frozen issues + graders).
- Reuse: `scripts/report-finding.sh`, `eval/cache_trace.py`, `eval/split.sh`, `eval/eval.sh` (`main_claude`, `usage_of`, `sub_of`, `report`, isolation), `yk stats`, `scripts/fake-project.sh` + `test-issue.sh`, `yukta-run-watcher` agent, the #1218 sub-issues call.

## Delivery (small PRs; the first two by hand, then the loop builds itself)
1. Ledger, size score, seeded draw, budget gate, `yk pick` for A and B + `test-loop.sh`.
2. Method A workflow (`solo`).
3. Analyzer: extract, judge, file (dry-run first), open-code notes.
4. Arm C: backend probe, codebase-memory-mcp pin and verify, CodeGraph fallback, rules per backend.
5. `yk loop report`, `yk loop sync` (CI, review, lead time), `yk loop themes`.
6. Golden-set tier: freeze issues, graders, replay command.

## Verification
- Offline `scripts/test-loop.sh` (fake claude and gh): draw recorded before the run; over 30 draws per stratum counts differ by at most 1; same seed reproduces; budget gate stops at $60; loop-changing issue flagged and excluded; size buckets and label override.
- Analyzer on the archived evaluation (`eval/results/2026-10-25-1207`): stage 1 flags the out-of-module fixers, the cache re-write turn and the B2 restart; `--dry-run` filing reports #1232 to #1261 as duplicates and files nothing.
- Statistics: report on synthetic ledgers with known effects (recovers a planted 2x cost difference at n=10 per method, and prints "no verdict" at n=4).
- Online smoke: a throwaway `dogfood-test` issue with stubbed gh, one run per method; then one real small issue and a read of the report and proposals before the second.
- Arm C: each backend verified on a worktree (IDE probe, codebase-memory-mcp for Java and Go, CodeGraph) before it can be drawn; a failing backend is skipped and logged.

## Risks (threats to validity)
- **Internal**: the loop edits itself (loop sha, loop-changing exclusion); shared caches and load across runs (serial runs, recorded time).
- **Construct**: cost and tokens are not value; quality proxies (CI, review, rework) are imperfect.
- **External**: one repository; issues from one epic skew toward dogfood work.
- **Conclusion**: low n (about 20 to 30 issues over 3 methods and 3 strata); the report states it and does not rank early.
- **Third-party binary**: pinned, checksum-verified, per-run MCP config, no install script, no config edits.
- **Analyzer noise**: files only with stage-1 evidence, dedupes, you approve every issue, precision is tracked.

## Sources
- METR RCT: https://thezvi.substack.com/p/on-metrs-ai-coding-rct, https://blog.robbowley.net/2026/04/04/metrs-developer-productivity-research-2026-update/
- AI Agents That Matter: https://agents.cs.princeton.edu/ ; HAL: https://hal.cs.princeton.edu/
- Anthropic, Demystifying evals for AI agents: https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents
- Adding Error Bars to Evals: https://arxiv.org/abs/2411.00640
- Efficient Benchmarking of AI Agents: https://arxiv.org/abs/2603.23749 ; tau-bench: https://arxiv.org/abs/2406.12045
- Crossover designs in SE experiments: https://arxiv.org/abs/2408.07594
- GQM: https://en.wikipedia.org/wiki/GQM
- OpenTelemetry GenAI spans: https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/
- DORA, SPACE in the AI era: https://getunblocked.com/blog/dora-metrics-ai-era/ , https://developertoolkit.ai/en/org/metrics-frameworks/
- CUPED and bandits: https://arxiv.org/abs/2606.18750 , https://arxiv.org/abs/1811.00457
- Error analysis (open and axial coding): https://atla-ai.com/post/automating-error-analysis
- codebase-memory-mcp: https://github.com/DeusData/codebase-memory-mcp
