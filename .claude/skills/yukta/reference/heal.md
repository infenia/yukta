# Self-healing runs (`yk check`)

`yk check` rewrites the staged `gradle-check` so a failing stage repairs itself before the run fails
(`stage_workflows` / `run_stages` in `examples/11-dogfood/scripts/_lib.sh`, policy `examples/11-dogfood/heal.json`).
Each stage is its own workflow `stage-<stage>` (nodes `run`, `apply`, outcome terminals `ok`, `retry`,
`escalate`, `failed`) with explicit exits:
- a deterministic fix where one exists (`spotless`: `spotlessApply`) makes the server's rule (`rules.json`)
  restart the stage;
- otherwise the driver runs up to two headless Claude Code attempts (`yukta-heal.sh`): attempt 1 fresh,
  attempt 2 a fork of the calling session (`--fixer auto`, the default; `--fixer fresh` for a change you
  did not make), then starts the stage again;
- a final `verify` re-runs every task when anything was healed, so `SUCCESS` means the whole tree passes.

Output: `STAGE <stage> <outcome> execution=<e>` lines, `HEAL` lines, then `RESULT`.

## Reading HEAL lines
`HEAL <stage> attempt=<n> mode=<auto|fresh|fork> fixed=<bool> files=<n> cost=$<x> summary=<text>` is one attempt.
- `ERROR <stage>: …` lines before a `HEAL <stage>` line are the failure being handled, not yours to fix.
- `summary=GUARD: …` means the attempt was rejected: it touched `config/`, `build-logic/`, `gradle/`,
  `.github/`, `.claude/` or the dogfood tooling, added a suppression, removed assertions, or changed nothing.
  Its edits stay in the tree: look at `git diff` and decide.
- `RESULT … healed=style:fix1,spotless:auto` lists what was fixed. Records: `.yukta-dev/heal/<session>/`.
- A failure after healing (`failed_node=<stage>`) is exhausted: take over, fix it, rerun `yk check`.
- Record a fixer that misbehaves (wrong fix, GUARD hit, cost over budget) with `yk finding`.

## Knobs
`YUKTA_HEAL_BUDGET` (USD per attempt, default 1), `YUKTA_HEAL_MODEL` (default sonnet). The fixer runs with
`YUKTA_HEAL_FIXER=1`, which lets it call `./gradlew` past the repo's hook; it never commits or pushes and
must not run with `claude --bare` (that loses the login). The server needs `YUKTA_PROCESS_ALLOW_ANY=true`
(`yk up` sets it).
