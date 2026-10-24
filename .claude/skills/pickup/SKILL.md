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

2. **Claim each before starting**, so a parallel session does not take the same issue. If it is already
   In progress, skip it. Otherwise:

   ```bash
   . .github/scripts/project-lib.sh
   PROJECT_OWNER=infenia PROJECT_NUMBER=5 PROJECT_TOKEN=$(gh auth token) \
     project_add_item "<issue url>" Status="In progress"
   ```

   If this fails (missing `project` scope: `gh auth refresh -s project`), stop and tell the user.

3. **Work one issue per branch** from `main`: read the issue and its acceptance criteria, implement,
   and verify with the `yukta` skill (`yukta-check.sh`), smallest test scope first.

4. **Open the PR** with `Fixes #<n>` and the `yukta-check.sh` RESULT line under Verification. The
   project moves it to In review.

5. **Report** per issue: PR link, gate result, anything left undone. If an issue cannot be finished,
   set it back to Ready (same command with `Status="Ready"`) and say why.
