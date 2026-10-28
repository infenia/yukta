# Heal runbook

> The loop is being rebuilt: `yk run issue|epic` is switched off (see `backup/README.md`), and
> the design is [DESIGN.html](DESIGN.html). Sections below that use those commands apply once they are restored.

How to run the dogfood check, watch heal work and improve it. The design is in [DESIGN.html](DESIGN.html) and the short guide in [README.md](README.md); this is the operator's manual.

## What costs money, what touches the outside

| Command | Spends | Touches |
|---|---|---|
| `yk check`, `yk gradle` | Claude only when a stage fails and heal runs a fixer ($ per attempt: `heal.json` `efforts` budgets) | your working tree (fixers edit it, never commit) |
| `yk run issue|epic` | an implementer ($5 cap) and fixers | the issue and the project board, a worktree, a pushed branch, a draft PR |
| `yk heal report|analyze|show` | nothing | nothing (reads `heal-events.jsonl`; events written without a run id take the run of the session's next result) |
| `eval/triage/eval.py` | nothing (local Ollama) | nothing |
| `eval/triage/collect.py` | nothing | `gh` reads of your CI |

## Where things are

| What | Where |
|---|---|
| the events log (all runs, all checkouts) | `$DEV_HOME/heal-events.jsonl` (`~/.local/state/yukta-dev/`), `YUKTA_HEAL_EVENTS` |
| the records of one run (attempts, outputs, exit codes) | `<checkout>/.yukta-dev/heal/<session>/` (wiped by the next `yk check`) |
| the heal policy (models, efforts, triage prompt) | `heal.json` |
| the triage evaluation: labels, data | `eval/triage/labels.json`, `eval/triage/data/` (not committed) |

## The improvement loop

1. **Run it.** `yk check` (or let the Stop hook do it). Nothing to configure: events are on by default.
2. **Look.** `yk heal report --since 7d`. Read it top to bottom: *where the time goes*, the stage table (a slow stage is a slow
   check, not a heal problem), the heals (solved at which rung), the triage block, the cost per rung, the failures that came back.
3. **Find what is wrong.** `yk heal analyze`. Each rule names a cause (README "Monitoring heal"). `heal-ledger.json` maps a rule to its GitHub issue: an open one prints `tracked in #N` and does not fail the command, a closed one is quiet until the rule fires again on events after the close (`REGRESSION`). Map a new finding to its issue there. For one run: `yk heal show --run <id>`.
4. **Fix it, and measure.**
   - triage falls back or is slow: `yk heal show | jq 'select(.event == "triage")'` (`error`, `raw`), `ollama ps`, `journalctl -u ollama`;
     shorten the brief (`yukta-heal.sh brief`, caps in `eval/triage/eval.py`);
   - triage rates too low or too high: `eval/triage/eval.py --split test` against the labelled failures, change the prompt in
     `heal.json`, compare variants (`--variant`), tune on `dev`, report on `test`;
   - a fixer wastes money or wanders: read its `fix` events (`summary`, `files`, `guard`), fix `fixer-rules.md` or the guard;
   - the same failure keeps coming: fix the cause, or its check.
5. **Grow the evaluation.** `eval/triage/collect.py` downloads new failed CI runs; label the new distinct failures in `labels.json`
   (`level`, `basis`, `confidence`, `set`; `exclude` for what is not a code failure).

## Knobs

`YUKTA_HEAL_EVENTS` (file, or `off`), `YUKTA_HEAL_BUDGET` (USD per attempt, set per effort by `heal.json`), `YUKTA_TRIAGE=off` (skip the
System One call: heal starts at the default level), `YUKTA_TRIAGE_URL` (another Ollama, default `http://localhost:11434`), `YUKTA_TRIAGE_MODEL` (another System One model, default `heal.json` `triage.model`; see "Choosing the System One model" in the README), `YUKTA_BUDGET_ISSUE` (USD per issue run),
`yk check --models sonnet,opus`, `yk check --triage off`, `yk check --pause before-opus`.
For issue runs: `YUKTA_IMPL_MODEL` (opus), `YUKTA_IMPL_BUDGET` (5), `YUKTA_BUDGET_EPIC`, `YUKTA_WARM_DELAY`, `YUKTA_NAV_BACKENDS`,
`YUKTA_NAV_IDE`, `YUKTA_NAV_IDE_URL`, `YUKTA_NAV_CBM_BIN`, `YUKTA_NAV_CBM_CACHE`, `YUKTA_NAV_INDEX_TIMEOUT`.

## When something is off

| Symptom | Cause and fix |
|---|---|
| `triage-fallbacks` with `error` | the model is missing or in an unsupported format (`ollama list`, `ollama pull`), Ollama is down, or the call took over 60 s: Ollama cuts a System One call off at 60 s and the machine reads about 50 tokens a second without a big GPU, so keep the brief near 5000 characters |
| the triage always answers `average` | the confidence floor (`minProbability` 0.3) sends vague answers to the default: look at `p` in the triage events and at `eval/triage` |
| `yk check` says another healing check is running | the watcher of the last run holds the lock for a few seconds; wait or `yk stop` |
| no events | `YUKTA_HEAL_EVENTS=off` is set, or the dev server was started before this change: `yk up` |
| `yk heal report` says no events for this selection | `--since`, `--run` or `--project` filter everything out |
| a fixer hit a guard (`guard-hits`) | it touched `config/`, `build-logic/`, `gradle/`, `.github/`, `.claude/`, the dogfood tooling, added a suppression or removed assertions; its edits stay in the tree: `git diff` |
| the dev server runs old code | `yk up` rebuilds from `origin/main`; the workflows come from your checkout, the plugins from the server |

## Tests

```bash
scripts/test-hooks.sh eval/triage/selftest.sh   # offline: the Claude Code hooks, the triage evaluation
scripts/test-plan.sh                             # offline: plan.sh's class-level test selection
../../.github/scripts/plan-vs-ci.test.sh          # offline, also in CI: plan.sh and ci-tasks.sh pick the same modules
scripts/test-stats.sh                            # offline: yk stats reads the heal events and its counts match them
scripts/test-yk.sh                              # on the dev server: a stopped foreground yk stops its run, --detach outlives, --stages
scripts/test-heal.sh                            # on the dev server (yk up), against a fake claude and a fake Ollama: about a minute, no API cost
```
`test-heal.sh` runs five scenarios through the real `check` workflow: two failing stages healed one after the other, a no-change attempt that
ends the ladder, an escalation, a failed verify that goes back to heal, and a triage that starts at `sonnet/low`. The tests of the switched-off
pieces (issue, epic, nav, steer, heal report) are in `backup/scripts/`.
