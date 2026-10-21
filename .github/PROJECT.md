# Work tracking: GitHub Issues and the Yukta Roadmap project

All bugs, risks, improvements and plans are tracked as issues in `infenia/yukta` and on one
org-level project, **Yukta Roadmap**. The Mintlify site (`docs/`) holds product documentation
only; do not record backlog items there.

## Issues

| What | Issue type | Form | Labels set by the form |
|---|---|---|---|
| Defect | Bug | Bug report | `needs-triage` |
| Defect found by the dogfood loop | Bug | Dogfood finding | `dogfood`, `needs-triage` |
| New capability or improvement | Feature | Feature request | `needs-triage` |
| Rated weakness, not yet a defect | Task | Risk | `risk`, `needs-triage` |
| Plan or evolution path | Epic | Epic | `epic` |
| Anything else (clean-up, chore) | Task | blank issue by a maintainer | |

- The kind of an issue is its **type**, not a label. The `bug` and `enhancement` labels are kept
  only for old issues.
- Area labels (`core`, `web`, `plugins`, ...) are the same labels the PR labeler sets, so issues
  and PRs filter alike. Labels are defined in [`labels.yml`](labels.yml) and synced by the
  `Sync labels` workflow; add a label there, not in the UI.
- An Epic's work items are its **sub-issues**. Order between them is an issue dependency
  ("blocked by").
- Close a defect with a PR that says `Fixes #<n>`. Close as *not planned* (with a comment) for
  dropped items and *duplicate* for duplicates.
- Long-form design text lives in `design/` in the repository and is linked from the Epic.

## Project fields

| Field | Type | Values |
|---|---|---|
| Status | single select | Triage, Backlog, Ready, In progress, In review, Done |
| Priority | single select | P0 (fix now), P1, P2, P3 (clean-up) |
| Severity | single select | Critical, High, Medium, Low (defects) |
| Module | single select | boot, build, cli, core, mcp, messaging, plugin-api, plugins, plus, persistence-postgres, workers, ui, web, documentation |
| Kind | single select | Defect, Risk, Improvement, Hardening, Dogfood, CI failure, Flaky test, Perf |
| Category | single select | Security, Correctness, Reliability, Availability, Performance, Scalability, Evolvability, Operability, Governance |
| Impact, Effort | number | 1 to 5 (risks; also useful for ranking anything) |
| Legacy ID | text | Id from the old Mintlify pages (B31, G1, DF-8, F12, `openOps`) |
| Iteration | iteration | 2-week iterations |
| Start date, Target date | date | Roadmap view |

Releases are **milestones** (`v0.1.0`, ...), not a field.

Severity says how bad a defect is; Priority says when it is worked on. A Low-severity bug can be
P1 when it blocks a release.

## Triage

1. New issues land in **Triage** with `needs-triage`.
2. A maintainer sets the type, Module, Priority (and Severity, or Impact and Effort), removes
   `needs-triage` and moves the item to **Backlog**, or closes it (duplicate, not planned,
   question answered).
3. **Ready** means the issue is clear enough to start: problem stated, acceptance criteria known.
4. **In progress** when someone is assigned and working. **In review** is set by the project when a
   PR is linked. **Done** is set when the issue closes or the PR merges.

Aim to triage within 5 working days. P0 issues get an assignee the same day.

## Setting up the project (once, by an org owner)

1. Create the **Epic** issue type: org settings → Planning → Issue types. (Bug, Feature and Task
   already exist.)
2. Run `.github/scripts/project-setup.sh infenia yukta` with a `gh` login that has the `project`
   scope (`gh auth refresh -s project`). It creates the project, links it to the repository and
   adds the custom fields. Re-running is safe. It prints what is left, which is the rest of this list.
3. Status options: rename and add them to match the table above.
4. Add an **Iteration** field (2 weeks).
5. Views:

   | View | Layout | Filter / grouping |
   |---|---|---|
   | Triage | board | `status:Triage`, sorted by created, oldest first |
   | Backlog by module | table | `is:open`, group by Module, sort by Priority |
   | Bugs | table | `type:Bug is:open`, group by Severity |
   | Risk matrix | table | `label:risk is:open`, group by Impact (descending), sort by Effort |
   | Current iteration | board | `iteration:@current` |
   | Roadmap | roadmap | `type:Epic`, Start date to Target date |
   | CI health | table | `label:ci-nightly,flaky-test` |
   | Dogfood | table | `label:dogfood` |

6. Built-in workflows (project → Workflows), all on:
   - **Auto-add to project**: repository `infenia/yukta`, filter `is:issue,pr is:open`.
   - **Item added to project**: Status = Triage.
   - **Item reopened**: Status = Backlog.
   - **Pull request linked to issue**: Status = In review.
   - **Item closed** and **Pull request merged**: Status = Done.
   - **Auto-archive items**: `is:closed updated:<@today-14d`.
   - **Auto-add sub-issues to project**.
7. For automation that writes to the project from Actions, create a GitHub App owned by the org
   with **Organization → Projects: read and write** and **Repository → Issues: read and write**,
   and install it on `infenia/yukta`. Then set:

   | Name | Kind | Value |
   |---|---|---|
   | `YUKTA_PROJECT_APP_CLIENT_ID` | org or repo variable | the App's client id |
   | `YUKTA_PROJECT_APP_KEY` | org or repo secret | the App's private key |
   | `YUKTA_PROJECT_NUMBER` | org or repo variable | the project number from its URL |

   Workflows mint a short-lived token from the App with `actions/create-github-app-token`;
   `GITHUB_TOKEN` cannot write org projects. Until all three are set, the workflows below still
   label and file issues and only skip the project.

## Automation that writes to the project

| Workflow | When | What it sets |
|---|---|---|
| `issue-triage.yml` | an issue is opened | area label from the form's Module answer; project item in Triage with Module, Severity, Priority, Category, Impact, Effort from the form and Kind from the labels and type |
| `nightly-report.yml` | a scheduled run fails and no tracking issue is open | the new `ci-nightly` issue gets the Bug type and a project item: Triage, Kind CI failure, Priority P1, Module build. The issue closes itself when the nightly is green, and the project moves it to Done |

| `nightly-report.yml` (`flaky-tests.sh`) | a scheduled CI run has a test that failed, then passed on retry | one `flaky-test` issue per test method (Bug type; Triage, Kind Flaky test, Priority P2, its module); a comment each time it is flaky again; closed after 7 days without a flaky run, checked on green runs only |
| `weekly-health.yml` | Mondays | rewrites the pinned **Project health** issue: open issues by type, untriaged for over a week, unassigned P0/P1, In progress with no activity for two weeks, open risks with impact 4 or 5, and code scanning alert counts (never the alerts themselves) |

Other automation around issues and pull requests:

| Workflow | What it does |
|---|---|
| `pr-title.yml` | fails a PR whose title is not a Conventional Commit (the squash commit on `main`), warns when a `feat`/`fix` PR links no issue, and sets the PR's `type/*` label from the title |
| `release.yml` + `.github/release.yml` | release notes are generated and grouped by label: breaking changes, security, features, bug fixes, docs, dependencies, other |
| `released.yml` | when a release is published, comments "Released in vX.Y.Z" on every issue a PR in it closed, and closes the milestone named like the tag |

All of them use `.github/scripts/project-lib.sh` (`project_add_item <url> Field=Value ...`), which looks up
fields and options by name and skips, with a warning, any that the project does not have.
`project-lib.test.sh` tests it, the triage mapping, flaky test tracking, the release follow-up and the
health report in the `lint-workflows` CI job.
