---
name: pickup
description: Use when asked to pick up, take or work on the user's assigned issues ("/pickup", "/pickup 3", "/pickup 123", "work on my issues"). Lists the issues assigned to the user that are Ready on the Yukta Roadmap project, claims each by moving it to In progress, then implements it and opens a PR.
argument-hint: "[count | issue number]"
allowed-tools: Bash, Read, Edit, Write
---

# Pick up assigned issues

Argument: a count (`3`, default 1) or an issue number (`123`).

1. **List candidates.** Assigned to the user and Ready on the project; never ask for numbers:

   ```bash
   gh issue list -R infenia/yukta --assignee @me --state open --json number,title,url -q '.[]|[.number,.url]|@tsv'
   gh project item-list 5 --owner infenia --limit 700 --format json \
     -q '.items[] | select(.status=="Ready") | [.content.number, .priority, .module, .title] | @tsv'
   ```

   Keep issues in both lists, highest Priority first (P0 before P3). A number argument takes that issue
   if it is assigned to the user. Anything assigned but not Ready lacks acceptance criteria: report it
   and skip it. Nothing left: say so and stop.

2. **Run each through Yukta.** One command does the rest (claim the issue as In progress, a worktree
   `../yukta-issue-<n>` on `issue/<n>` from `origin/main`, a headless implementer, the self-healing check
   with the fixers resuming the implementer, a draft PR with `Fixes #<n>` and the check's RESULT line):

   ```bash
   yk run issue <n> --detach          # STARTED execution=<e> session=yukta-dev
   ```

   The run waits at two checkpoints for you (`yk signals <e>`, then `yk approve <e> <signal>` or
   `yk reject <e> <signal>`):
   - `before-implement`: the brief is `.yukta-dev/issue-<n>/brief.md` in the main checkout; edit it if the
     issue needs a hint or a narrower scope, then approve.
   - `before-pr`: the check has passed on the worktree; look at the diff (`git -C ../yukta-issue-<n> diff
     origin/main`), then approve to open the draft PR.
   `--no-pause before-implement,before-pr` skips them. Watch with `yk watch yukta-dev <e>` under the Monitor tool
   (or the `yukta-run-watcher` subagent), `yk tree <e>` for where it is. With several issues, start them one at
   a time and keep the checkpoints: each run builds in its own worktree but shares the dev server.

3. **Read the end.** `RESULT status=SUCCESS` means the draft PR is open (its URL is in the run's log). `status=ESCALATE`
   means the run gave up: the issue is Ready again and the worktree is kept, so read the `NODE`/`HEAL` lines, fix
   it in the worktree yourself (or reject and rewrite the brief) and run `yk run issue <n>` again. Report per issue:
   PR link, gate result, anything left undone.

If `yk run issue` reports that the issue is not Ready, it has no acceptance criteria: report it and skip it.
