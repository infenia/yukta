# Token evaluation harness

This is the reference for the harness. For how to plan, run and read an evaluation end to end, start with the [evaluation guide](../EVALUATION.md).

Measures what the dev loop costs in tokens and dollars on a real issue, and compares two ways of using Yukta from Claude Code:

| Arm | Who does the work | What Yukta does |
|---|---|---|
| **A** | One Claude Code session fixes the issue itself. | Only checks: `yk gradle` and `yk check --no-heal`. It never fixes. |
| **B** | The same Claude Code session is an *orchestrator*: it starts `yk run issue` and waits. | The workflow runs a slim headless implementer, the check, and fixer sessions that resume the implementer. |

Both main sessions start identically (same model, tools, your own Claude Code setup, same budget), so the difference is who does the work. Arm B's total counts the main session **and** every sub-flow Claude session. The first evaluation (issue #1207, 3 runs per arm) is archived in `results/2026-10-25-1207/`, with the report page generated from it.

## Prerequisites

- `claude` logged in, `jq`, `git`, `gh` (only for `eval.sh fixture`), `python3`; `go` if the issue's grading uses it.
- The dev server running and built from the code under test: `yk up` (see the parent [README](../README.md)). Arm B runs on it. To test unmerged engine or `yk` changes, build a server from your branch with `YUKTA_DEV_HOME`, `YUKTA_DEV_PORT` and `YUKTA_DEV_MAIN_REF` set (the same values for the harness), as `scripts/test-epic.sh` expects.
- Money: every real run spends up to `EVAL_BUDGET` (default $5) for the main session, and arm B up to the same again per implementer and fixer session. Plan on a few dollars per run; three runs per arm is about $15 to $30 in the worst case.

## Quick start

```bash
cd examples/11-dogfood/eval
./selftest.sh                          # offline check of the harness, no spend (do this first)
yk up                                  # the dev server
export EVAL_BASE=$(git rev-parse origin/main)   # pin one base commit for all runs (default: fetch origin/main)
./eval.sh run A 1                      # one run of arm A (B and C likewise: C is B with a code-navigation index); prints result.json
./eval.sh run B 1
# alternate the arms: A1 B1 A2 B2 A3 B3
./eval.sh validate                     # the grader of EVAL_ISSUE must fail on the base commit and pass with its reference fix
./eval.sh report                       # per-run and per-arm tables (median [min..max])
./split.sh                             # where arm B's sub-flow money went
python3 -I cache_trace.py B1 --summary # per-turn prompt-cache trace of a run
python3 -I cache_trace.py --worktree ../yukta-issue-1234 --json   # one issue worktree, machine-readable (the analyzer uses it)
```

Runs go to `$EVAL_DIR/runs/<arm><n>/` (default `<checkout>/.yukta-dev/eval`, git-ignored). Run them **serially**, alternating arms, so neither arm always runs first and parallel load does not skew wall time.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `EVAL_ISSUE` | `1207` | the issue to run; needs `issues/<n>/` (below) |
| `EVAL_BASE` | fetched `origin/main` of `EVAL_SRC` | the commit every run starts from; pin it for a comparable set |
| `EVAL_SRC` | this checkout | repository cloned for each run |
| `EVAL_ISSUES_DIR` | `eval/issues` | where `issues/<n>/` live; the self-tests use a temporary one |
| `EVAL_DIR` | `.yukta-dev/eval` | where runs are written |
| `EVAL_YK` | `../yk` | the `yk` the sessions use |
| `EVAL_MODEL` | `opus` | main-session model, and the implementer's (`YUKTA_IMPL_MODEL`) |
| `EVAL_BUDGET` | `5` | USD cap for the main session and for each implementer/fixer session |
| `EVAL_MAIN_TURNS` | `60` | turn cap of the main session |
| `EVAL_CLAUDE` | `claude` | the binary; `fake-claude.sh` in the self-test |

## What one run does

1. **Prep.** A fresh clone of `EVAL_SRC` with a bare `origin` whose `main` is `EVAL_BASE`. A stub `gh` serves the saved issue and logs everything else; a stub project-status command logs. Nothing reaches GitHub: the real issue is not claimed, moved or commented on, and no PR opens.
2. **Main session.** `claude -p` in the clone with the arm's prompt (see `prompt_a` and `prompt_b` in `eval.sh`), `--output-format json`, the budget and turn caps, `git commit`, `git push` and `gh` refused. Arm B's orchestrator is told not to edit code and to run `yk run issue <n> --no-pause before-implement,before-pr` in the foreground (Bash timeout 600000 ms): a headless `claude -p` is not woken by background tasks, so it has to wait in a foreground call. It may restart the run once after an `ESCALATE`.
3. **Collect.** The main session's `usage` and `total_cost_usd` from the JSON. For arm B also the sub-flow records: `yukta/.yukta-dev/issue-<n>/implement.json` and `yukta-issue-<n>/.yukta-dev/heal/check/<stage>-<attempt>.json`.
4. **Grade.** The issue's hidden test is copied in, run, and removed; then the suite command runs. Written to `result.json` with the totals.

`result.json` fields: `arm`, `run`, `base`, `model`, `wall_s`, `main` and `sub` (`input`, `cache_creation`, `cache_read`, `output`, `cost`, plus `turns` or `attempts`/`fixed`), `total`, `grade` (`hidden_pass`, `suite_pass`, `changed_files`), `answer` (the end of the main session's reply, which carries the workflow's `RESULT` line).

## Adding an issue

```bash
./eval.sh fixture 1234                 # saves issues/1234/issue.json from gh (the stub serves it)
```

Then add to `issues/1234/`:

- `grade.env`: `MODULE` (the issue's module; arm A's check scope and `split.sh`'s "own module"), `HIDDEN_DEST` (path inside the repo where the hidden test is copied for grading, then removed), `HIDDEN_CMD` and `SUITE_CMD` (shell commands run in the repo root), and optionally `HIDDEN_SRC` (the test's file name in this folder, default `hidden_test.go`). See `issues/1207/grade.env`.
- the hidden test file itself.

Write the hidden test against the behaviour the issue asks for, never against its own solution, and **check it** before spending money: it must fail on the base commit and pass with a reference fix (`issues/1207/reference-fix.patch` is the one used for #1207). A test that calls an internal function breaks if a solver renames it; none did for #1207, but flag such a run for manual review instead of scoring it a failure.

Pick an issue that is small, clearly right or wrong, unfixed on the base commit, and whose reference fix is known.

## Reading the results

- **Correct is not delivered.** `grade` looks at the code in the clone or worktree. Arm B can hold correct code and still end `ESCALATE` (run B2 in the first evaluation): read the `RESULT` line in `answer` and count only `SUCCESS` as delivered.
- **Cost is Claude Code's `total_cost_usd`** at list prices. Under a subscription the bill differs, but token counts and ratios hold.
- **Records can undercount.** `implement.json` and `<stage>-<n>.json` are overwritten if the orchestrator restarts the run in the same worktree (yukta issue #1250); the first run's tokens are then only in the transcripts. Use `cache_trace.py`, whose totals per session match the records when nothing was overwritten, and add the missing run to `extra.json` for `report/make_report.py`.
- **`split.sh`** separates the implementer, fixer attempts that touched `MODULE/` and attempts that did not. A large "other" share means the check ran into failures the change did not cause (first evaluation: 81% of arm B's spend).
- **Check scope differs between the arms on purpose.** Arm A is told to finish with `yk check --no-heal -m <MODULE>`; arm B's workflow chooses its own scope (the whole repository at the time of the first evaluation).
- **Cache.** `cache_trace.py` reads Claude Code's session transcripts (`$CLAUDE_CONFIG_DIR` or `~/.claude`, `projects/<worktree path>`) and prints write and read tokens per turn. A turn flagged `re-wrote context` read far less than the previous turn's context: the cache did not cover it. The first evaluation found one such turn per run, the first resumed fixer turn, caused by a different `--json-schema` on resume (yukta issue #1248).
- **Tooling differs by role.** The main session gets your whole Claude Code setup (plugins, skills, MCP servers); the sub-flows (`yk_claude` in `scripts/_claude.sh`) get six built-in tools and nothing else. To see what a session is given, run it with `--output-format stream-json --verbose` and read the `system`/`init` event. Count what a run actually used with the `tool_use` blocks of its transcript. In the first evaluation neither arm had an IDE index or a CodeGraph index (fresh clones have none), so the benefit of such tools is not measured; an arm C with a working index would measure it (yukta issue #1251).
- **Small samples.** Three runs per arm show a gap, not a precise number; arm B's spread follows how many fixer attempts a run needed.

## Known limitations

- The grading commands are whatever `grade.env` says, but only a Go issue (#1207) has been run so far; a Java issue would use `HIDDEN_CMD='./gradlew ...'` through `yk gradle` and has not been tried.
- The report text in `report/template.html` is written for the first evaluation. For another issue use `eval.sh report` and `split.sh`, or copy and adapt the template (`report/make_report.py <runs dir> <out.html>`; an optional `extra.json` next to the runs dir adds `lost_runs` and `resume_rewrites`).
- Both arms load the project's `.claude` settings only if the workspace is trusted. A fresh clone is not, so each session prints "workspace not trusted: ignoring ... permissions.allow entries". Tools are passed explicitly, so the runs work, and it affects both arms alike.
- Runs in parallel are not supported (one gradle run per checkout, and the cost comparison needs a quiet machine).
- Arm B uses the dev server on `YUKTA_DEV_PORT`; a server built from a different commit than `EVAL_BASE` measures the wrong code.

## Files

| Path | Purpose |
|---|---|
| `eval.sh` | the harness: `fixture`, `run`, `collect`, `grade`, `report` |
| `split.sh` | arm B sub-flow cost by where the fixer worked |
| `cache_trace.py` | per-turn prompt-cache trace from the session transcripts |
| `selftest.sh`, `fake-claude.sh`, `issues/selftest/` | offline check of the harness; run it after changing `eval.sh` |
| `golden.sh`, `golden-report.py`, `golden/set.json`, `golden-selftest.sh` | the golden set: frozen issues replayed in every method with several trials on a loop change; its report; the set; its offline test (see the [guide](../EVALUATION.md), section 6.3) |
| `issues/<n>/` | the saved issue, `grade.env`, the hidden test (and a reference fix for #1207) |
| `report/` | `make_report.py` and `template.html`: the page of the first evaluation |
| `results/<date>-<issue>/` | archived run data (`runs/`, `extra.json`) and the generated `report.html` |
