# Ticket pipeline (work in progress)

Turns a GitHub issue into a reviewed, tested draft PR: an architect writes a spec and a test-case table, a test writer and an implementer work one small step at a time inside hard guardrails, a reviewer signs off. Design: `design/plans/ticket-pipeline.md` (Epic #1872); answers to the open questions: `verify.md`.

| Path | What |
|---|---|
| `schemas/` | JSON Schemas of every artifact the roles exchange (spec, step packet, step result, failure digest, revision, review, test-writer result) |
| `fixtures/<schema>/` | one `valid.json` and several `invalid-*.json` per schema |
| `project.json` | the project profile: commands, conventions, high-risk paths, limits, budgets |
| `ticketkit` | the deterministic tools (python3 `jsonschema` >= 4.18 needed): `validate-schema NAME FILE`; `init ISSUE` (run dir, branch, worktree; `SKIPPED` and exit 3 without acceptance criteria or when the branch exists); `route RUN_DIR` (models, effort, `tests_mode` into `routing.json`); `validate-spec RUN_DIR` (design 8.1 and the sizing lint 11.3; writes `spec/validation.json` and `spec/spec.hash`); `gate-tests RUN_DIR` (after the test writer: applies the stubs, checks only stubs and locked tests changed, `[T#]` tags, compile, a red check per case from the JUnit / Vitest / `go test -json` report, trivial-test lint; then locks the tests in `spec/locked.json` and writes `spec/test_map.json`) |
| `../11-dogfood/fixer-rules/ticket-*.md` | the constant role files (`architect`, `test-writer`, `implementer`, `reviewer`), picked by `YK_ROLE` in `claude.sh` |
| `scripts/test-schemas.sh` | offline test (fake claude, no API cost) of the schemas, fixtures and roles |
| `scripts/test-spec.sh` | offline test of `init`, `route` and `validate-spec` on a throwaway git repo (design 13.1 items 5, 18, 19, 22; 15.13 items 24, 29) |
| `scripts/test-gate.sh` | offline test of `gate-tests` with a fake runner (design 13.1 items 6, 14, 15, 16, 17) |
