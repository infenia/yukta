# Hidden for the rebuild

Step 1 of the rebuild (DESIGN.html) exposes only `yk gradle`. Everything else the agent used to be told
about is moved here, with the repo paths mirrored (`dot-claude/` is `.claude/`). To make the next workflow
visible, move its pieces back and re-add the matching lines to the skill, CLAUDE.md and the `yk` usage header.

| Here | Back to | Needed for |
|---|---|---|
| `dot-claude/skills/yukta/SKILL.md` | `.claude/skills/yukta/SKILL.md` | the full skill text (check, heal, runs, dogfood) |
| `dot-claude/skills/yukta/reference/` | `.claude/skills/yukta/reference/` | control, heal, server, dogfood-loop |
| `dot-claude/skills/pickup/` | `.claude/skills/pickup/` | `yk run issue|epic` |
| `dot-claude/commands/` | `.claude/commands/` | /yk-check /yk-up /yk-finding /yk-pickup |
| `dot-claude/agents/yukta-run-watcher.md` | `.claude/agents/` | watching detached runs |
| `dot-claude/hooks/yk-hook.sh` | `.claude/hooks/` | the old Stop nudge (module-scoped `yk check -m`, issue findings); the live hook has the Stop nudge for plain `yk check` |
| `CLAUDE.md`, `dot-claude/rules/git-workflow.md` | repo root, `.claude/rules/` | the full guide |
| `yk.full.sh` | (reference) | the full `yk` usage text |

The workflows (`check`, `heal`, `run-issue`, ...) and their scripts were not removed: they still work from the
command line, they are just not advertised. `scripts/test-hooks.sh` asserts the old Stop/deny texts and fails
until the hook is restored.

Step 2 (merge): `gradle` and `tool` are one workflow, `tool` (concurrency group `tool`, 2 places); `yk gradle` and `yk tool [-C dir] cmd` start it. The old `gradle` workflow is no longer in `session.json`.

Step 5 (heal): `yk check` heals through the design's own workflows `claude`, `attempt`, `fix-from` and `heal` (`scripts/gen-heal.py`, steps in `scripts/yukta-heal.sh`, `scripts/claude.sh`). Removed with it: the runtime-generated `check` (`heal_workflow`), `heal_subflows`, `scope_to_modules`, `yk check-heal`, the `gradle-check`, `gradle-run`, `check-plain` and `quick-check` workflows. Kept here: the old `yukta-heal.sh` (`scripts/yukta-heal.legacy.sh`), `test-self-heal.sh`, `run.sh`. `yk run issue|epic` is switched off until the issue workflows are rebuilt on the new check. Still hidden: the steering commands in the skill, the Stop hook.

Step 6 (cleanup): moved here, with the repo paths mirrored: `yk.run.sh` (`cmd_run` and `run_layered`, the `run`, `heal` and `finding` entries of the `yk` dispatcher),
`scripts/_lib.issue.sh` (the issue and epic workflow generators and `dev_session`, cut from `scripts/_lib.sh`), `scripts/yukta-issue.sh`, `scripts/nav.sh`,
`nav/` (the code-index rules), `scripts/report-finding.sh`, (`yk heal report|analyze|show` came back out of it in #1277) and the tests of those pieces
(`test-issue.sh`, `test-epic.sh`, `test-nav.sh`, `test-steer.sh`, `test-hooks.stop.sh` for the Stop nudge). The issue plumbing was
removed from the live code: the `issueState`, `budgetIssue` and `navFile` start keys, `YK_NAV`, the nav.json lookup in `scripts/_claude.sh` and the issue
budget check in `yukta-heal.sh prepare` (exit 5); restore them with the issue workflows. `test-steer.sh` and `fake-project.sh` (moved with them) use the old `yk check -p` flags.
