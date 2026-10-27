# Self-healing runs (`yk check`)

`yk check` runs ONE workflow, `check`, built from the staged `gradle-check` (`heal_workflow` in
`examples/11-dogfood/scripts/_lib.sh`, policy `examples/11-dogfood/heal.json`). A failing stage repairs itself
before the run fails, by calling the `heal` workflow and waiting for it:
- a deterministic fix first where one exists (`spotless`: `spotlessApply`);
- then `heal`: a System One triage rates the failure from its problem lines in three levels (`easy` = haiku at medium
  effort, `average` = sonnet at medium, the default, `hard` = sonnet at high; a failed or vague triage starts at the default)
  and picks the starting model and effort; `fix` runs that model at `low → medium → high`, each
  attempt time-boxed and followed by a re-run of the failing check; a model that fails at every effort hands over
  to the next one (`haiku → sonnet → opus`) at `low`; when opus has failed too the run fails
  (`yk check --models sonnet,opus` limits the models, `--triage off` skips the triage);
- a healed stage re-runs only itself; the analyzers (style, spotbugs, cli) run in parallel and share ONE
  `analyzers.heal`; `licenses` and `security` fail at once;
- a final `verify` re-runs every task when anything was healed, so `SUCCESS` means the whole tree passes.
An issue run (`yk run issue`) uses the same `check`, so its fixers are fresh sessions too; nothing resumes the implementer.

Output: the problem lines of the run, `HEAL` lines, `SLOW` lines, then `RESULT`.

## Reading HEAL lines
`HEAL <stage> attempt=<n> mode=<auto|haiku|sonnet|opus> [effort=<low|medium|high>] fixed=<bool> files=<n> cost=$<x> summary=<text>` is one attempt.
- `ERROR <stage>: …` lines before a `HEAL <stage>` line are the failure being handled, not yours to fix.
- `summary=GUARD: …` means the attempt was rejected: it touched `config/`, `build-logic/`, `gradle/`,
  `.github/`, `.claude/` or the dogfood tooling, added a suppression, removed assertions, or changed nothing.
  Its edits stay in the tree: look at `git diff` and decide.
- `RESULT … healed=style:haiku/low,spotless:auto` lists what was fixed (`analyzers:<model>/<effort>` for the shared analyzer
  chain). Records, with token usage: `.yukta-dev/heal/<session>/`.
- A failure after healing (`failed_node=<stage>`, or the analyzers still failing) is exhausted: take over, fix
  it, rerun `yk check`.
- Record a fixer that misbehaves (wrong fix, GUARD hit, cost over budget) with `yk finding`.

## Extra stages and the checkpoint
`ui`, `docs`, `reuse` and `actionlint` run as analyzers when their tool is installed (a `WARN` otherwise;
`YUKTA_CHECK_EXTRAS=off` leaves them out). `yk check --pause before-opus` stops in front of every opus fixer
until the run is approved, rejected or `YUKTA_PAUSE_SECONDS` (600) pass; it is off by default.

## Knobs
`YUKTA_HEAL_BUDGET` (USD per attempt, default 1). The fixer runs with `YUKTA_HEAL_FIXER=1`, which lets it
call `./gradlew` past the repo's hook; it never commits or pushes and must not run with `claude --bare`
(that loses the login). The server needs `YUKTA_PROCESS_ALLOW_ANY=true` (`yk up` sets it).

## Monitoring
Every step appends a line to `~/.local/state/yukta-dev/heal-events.jsonl` (`YUKTA_HEAL_EVENTS`; `off` disables). `yk heal report` shows where the heal time and money went, `yk heal analyze` names what looks wrong (noop fixers, triage fallbacks, repeated failures), `yk heal show` prints the events. Details: `examples/11-dogfood/README.md`, "Monitoring heal".
