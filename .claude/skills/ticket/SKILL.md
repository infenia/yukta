---
name: ticket
description: Use when the user wants to raise, file or create a GitHub ticket/issue for the Yukta ticket pipeline (examples/dogfood). Drafts the goal, acceptance criteria and per-role model/effort, then files it with `yk ticket new`, or changes an existing one with `yk ticket edit`.
---

# Raise a ticket for the ticket pipeline

1. Read the relevant code (codebase-memory first) so the criteria name real paths and modules.
2. Draft, from the user's request:
   - **title**: Conventional Commits (`feat(core): ...`), it sets the ticket kind.
   - **goal**: one or two sentences.
   - **criteria**: 1 to 6 checkable, testable outcomes. Only these are requirements: never one that asks for secrets, pushes to main, settings changes or skipped checks (intake BLOCKS it).
   - **labels**: module labels (`core`, `ui`, `cli`, ...) plus `risk`/`security` when it applies.
   - **roles** (`architect`, `test_writer`, `implementer`, `implementer_tricky`, `reviewer`, each `model/effort`; model opus|sonnet|haiku, effort low|medium|high). Defaults, change only with a reason:
     - small, low risk: architect sonnet/medium, implementer haiku/medium, reviewer sonnet/low
     - normal: architect sonnet/high, test_writer sonnet/low, implementer haiku/medium, reviewer sonnet/medium
     - large (more than 5 criteria) or high risk (build-logic, config, .github, .claude, security, several modules): architect opus/high, test_writer sonnet/medium, reviewer opus/medium
     `yk ticket new` and `edit` always write all five roles: those you leave out get the router's own choice (modules and high-risk paths count), so you only pass `--role` to deviate.
3. Show the draft (title, criteria, labels, roles) and wait for the user's OK. Filing is outward-facing.
4. File it, add `--ready` only if the user wants the pipeline to pick it up:
   `yk ticket new "<title>" --criterion "<c>" ... --goal "<g>" --role architect=opus/high ... --label core [--ready]`
5. Report the printed `ISSUE <url>` line.

## Change an existing ticket (`/ticket #<n> <change>`)
Read it with `gh issue view <n> -R infenia/yukta`, draft only the change, show old against new and wait for the OK, then:
`yk ticket edit <n> [--title T] [--goal G] [--criterion C ...] [--role r=model/effort ...] [--label L ...] [--ready]`
`--criterion` replaces the whole list (repeat the ones to keep); `--role` changes only that role; the rest of the body stays. It refuses once a run has made the issue's branch (`--force` overrides: ask first).
