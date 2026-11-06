---
name: ticket
description: Use when the user wants to raise, file or create a GitHub ticket/issue for the Yukta ticket pipeline (examples/dogfood). Drafts the goal, acceptance criteria and sizing facts the router picks models from, then files it with `yk ticket new`, or changes an existing one with `yk ticket edit`.
---

# Raise a ticket for the ticket pipeline

The pipeline is built to cost less without losing quality. You record **facts**, code picks the models, and the gates and the retry
ladder make up for a cheap start. Don't choose models by feel.

1. Read the relevant code (codebase-memory first) so the criteria and sizing name real paths.
2. Draft, from the user's request:
   - **title**: Conventional Commits (`feat(core): ...`). It sets the kind, and the kind sets the tests mode: `chore`/`docs` use the
     existing tests (no test writer), `fix` writes a repro only, `feat`/`refactor` write the case table up front. Pick the true one.
   - **goal**: one or two sentences.
   - **criteria**: 1 to 6 checkable outcomes, each one a gate can check (a command that passes, a `grep` with no hits). The count sets
     the size (2 or fewer S, 5 or fewer M, more L). Don't split one outcome into several. **Make every decision in the ticket** ("`/`
     redirects to `/control`", not "decide where `/` goes"): an open choice costs architect effort and can't be reviewed. Never a
     criterion that asks for secrets, pushes to main, settings changes or skipped checks (intake BLOCKS it).
   - **labels**: module labels (`core`, `ui`, `cli`, ...) plus `risk`/`security` when it applies.
   - **sizing**, the facts the router sizes by. Report them, don't grade:
     - `--file`: every path or glob the change writes or deletes (a directory means everything in it, a path that doesn't exist yet is
       a new file).
     - `--change`: the kinds, from `delete rename rewire docs config new_logic concurrency schema api_contract security`.
     - `--open-decisions N`: choices the ticket still leaves to the architect (aim for 0).

     Code takes the higher of this and the criteria/labels triage. Sizing can only raise the size and risk, so leaving a file out
     doesn't make the ticket cheaper, it only makes the route less accurate.
   - **roles**: leave them out. The router writes its choice as `(router)` lines that don't bind, so the run routes from the facts.
     Pass `--role architect=opus/high` only with a reason the facts can't express, and say the reason to the user.
3. Run `yk ticket new ... --dry-run`. It prints a `ROUTE complexity=.. risk=.. static=.. sizing=.. (reasons)` line, `NOTE`s for sizing
   paths that aren't in `origin/main` (fix typos), and the body. Show the user the draft and the ROUTE line, then wait for their OK.
   Filing is outward-facing.
4. File it with the same command without `--dry-run`. Add `--ready` only if the user wants the pipeline to pick it up:
   `yk ticket new "<title>" --criterion "<c>" ... --goal "<g>" --file <p> ... --change <k> --label ui [--ready]`
5. Report the printed `ISSUE <url>` line.

## Change an existing ticket (`/ticket #<n> <change>`)
Read it with `gh issue view <n> -R infenia/yukta`, draft only the change, run `yk ticket edit <n> ... --dry-run` to show old against new
and the ROUTE, wait for the OK, then run the same command without `--dry-run`:
`yk ticket edit <n> [--title T] [--goal G] [--criterion C ...] [--file P ..] [--change K ..] [--open-decisions N] [--role r=model/effort|r=auto ...] [--label L ...] [--ready]`
- `--criterion` replaces the whole list (repeat the ones to keep).
- A sizing flag replaces that key and keeps the others.
- `--role` changes only that role. `r=auto` (or `all=auto`) hands a role back to the router. Tickets filed before the `(router)` rule
  have five bare (binding) role lines: offer `--role all=auto` when you edit one.
- The rest of the body stays.

It refuses once a run has made the issue's branch (`--force` overrides: ask first).
