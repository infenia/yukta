# Ticket pipeline (work in progress)

Turns a GitHub issue into a reviewed, tested draft PR: an architect writes a spec and a test-case table, a test writer and an implementer work one small step at a time inside hard guardrails, a reviewer signs off. Design: `design/plans/ticket-pipeline.md` (Epic #1872); answers to the open questions: `verify.md`.

| Path | What |
|---|---|
| `schemas/` | JSON Schemas of every artifact the roles exchange (spec, step packet, step result, failure digest, revision, review, test-writer result) |
| `fixtures/<schema>/` | one `valid.json` and several `invalid-*.json` per schema |
| `project.json` | the project profile: commands, conventions, high-risk paths, limits, budgets |
| `ticketkit` | the deterministic tools; so far `validate-schema NAME FILE` (needs python3 `jsonschema` >= 4.18) |
| `../11-dogfood/fixer-rules/ticket-*.md` | the constant role files (`architect`, `test-writer`, `implementer`, `reviewer`), picked by `YK_ROLE` in `claude.sh` |
| `scripts/test-schemas.sh` | offline test (fake claude, no API cost) of the schemas, fixtures and roles |
