# Self-healing runs (`yk check`)

`yk check` runs ONE workflow, `check`, built from the staged `gradle-check` (`heal_workflow` in
`examples/11-dogfood/scripts/_lib.sh`, policy `examples/11-dogfood/heal.json`). A failing stage repairs itself
before the run fails, climbing a fixed ladder of rungs:
- a deterministic fix where one exists (`spotless`: `spotlessApply`);
- then headless Claude Code on the ladder `haiku → sonnet → opus` (`yukta-heal.sh`; `--ladder sonnet,opus`
  for one run), each rung given the earlier attempts' summaries;
- a healed stage re-runs only itself; the analyzers (style, spotbugs, cli) run in parallel and share ONE
  fixer chain; `licenses` and `security` fail at once;
- a final `verify` re-runs every task when anything was healed, so `SUCCESS` means the whole tree passes.

Output: the problem lines of the run, `HEAL` lines, `SLOW` lines, then `RESULT`.

## Reading HEAL lines
`HEAL <stage> attempt=<n> mode=<auto|haiku|sonnet|opus> fixed=<bool> files=<n> cost=$<x> summary=<text>` is one attempt.
- `ERROR <stage>: …` lines before a `HEAL <stage>` line are the failure being handled, not yours to fix.
- `summary=GUARD: …` means the attempt was rejected: it touched `config/`, `build-logic/`, `gradle/`,
  `.github/`, `.claude/` or the dogfood tooling, added a suppression, removed assertions, or changed nothing.
  Its edits stay in the tree: look at `git diff` and decide.
- `RESULT … healed=style:haiku,spotless:auto` lists what was fixed (`analyzers:<model>` for the shared analyzer
  chain). Records, with token usage: `.yukta-dev/heal/<session>/`.
- A failure after healing (`failed_node=<stage>`, or the analyzers still failing) is exhausted: take over, fix
  it, rerun `yk check`.
- Record a fixer that misbehaves (wrong fix, GUARD hit, cost over budget) with `yk finding`.

## Knobs
`YUKTA_HEAL_BUDGET` (USD per attempt, default 1). The fixer runs with `YUKTA_HEAL_FIXER=1`, which lets it
call `./gradlew` past the repo's hook; it never commits or pushes and must not run with `claude --bare`
(that loses the login). The server needs `YUKTA_PROCESS_ALLOW_ANY=true` (`yk up` sets it).
