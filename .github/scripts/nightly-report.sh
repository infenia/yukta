#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Tracks a scheduled (nightly) workflow run in GitHub issues:
#   failure  -> one issue per distinct failure (auto-found.sh: a repeat is a comment, not a new issue);
#   success  -> close an older "Nightly failure: <workflow>" issue, and count the run for the auto-found
#               issues of the workflow (closed after three green runs in a row).
# The report lists the failed jobs and steps, and the failed and flaky tests from the build-reports
# artifacts. Every run also writes test time per shard to the job summary, for balancing the shards.
#
# Flaky tests are tracked one issue per test by flaky-tests.sh. New issues get the Bug type and, when the
# project is configured (project-lib.sh), are put on the Yukta Roadmap project in Triage.
#
# Env: GH_TOKEN, REPO, RUN_ID, RUN_URL, WORKFLOW, CONCLUSION, HEAD_SHA;
#      optional PROJECT_TOKEN, PROJECT_OWNER, PROJECT_NUMBER
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=project-lib.sh
. "$here/project-lib.sh"
label=ci-nightly
title="Nightly failure: $WORKFLOW"
sha=${HEAD_SHA:0:7}
today=$(date -u +%Y-%m-%d)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
report="$work/report.md"

# Shards upload their JUnit XML as build-reports-<shard>; other workflows have none.
reports=false
if gh run download "$RUN_ID" -R "$REPO" -p 'build-reports-*' -D "$work/reports" 2>/dev/null; then
  reports=true
fi
# The perf workflows upload their k6 summaries as perf-*; other workflows have none.
gh run download "$RUN_ID" -R "$REPO" -p 'perf-*' -D "$work/perf" 2>/dev/null || true

# Flaky tests get their own issues (flaky-tests.sh); a problem there must not stop the run report.
if [ "$reports" = true ]; then
  "$here/flaky-tests.sh" "$work/reports" || echo "warning: flaky test tracking failed" >&2
fi

issue=$(gh issue list -R "$REPO" --label "$label" --state open --limit 100 --json number,title |
  jq -r --arg t "$title" 'map(select(.title == $t)) | first | .number // empty')

if [ "$CONCLUSION" = success ]; then
  [ "$reports" = false ] || python3 -I "$here/junit-failures.py" --durations "$work/reports" >>"${GITHUB_STEP_SUMMARY:-/dev/null}"
  if [ -n "$issue" ]; then
    gh issue close "$issue" -R "$REPO" --reason completed \
      --comment "The nightly **$WORKFLOW** run is green again on \`$sha\` ($today, [run]($RUN_URL)). Closing."
    echo "Closed #$issue: $WORKFLOW is green again" >>"${GITHUB_STEP_SUMMARY:-/dev/null}"
  fi
  "$here/auto-found.sh" --green
  exit 0
fi

{
  echo "### $today — \`$sha\` — $CONCLUSION ([run]($RUN_URL))"
  echo
  echo "#### Failed jobs"
  echo
  gh api "repos/$REPO/actions/runs/$RUN_ID/jobs?per_page=100" --paginate |
    jq -r '.jobs[] | select(.conclusion == "failure" or .conclusion == "timed_out" or .conclusion == "cancelled")
      | "- [\(.name)](\(.html_url)) — \(.conclusion)"
        + ([.steps[]? | select(.conclusion == "failure") | .name]
           | if length > 0 then "; failed step: " + join(", ") else "" end)'
  echo
  [ "$reports" = false ] || python3 -I "$here/junit-failures.py" "$work/reports"
} >"$report"

# Issue bodies and comments are capped at 65536 characters.
if [ "$(wc -c <"$report")" -gt 60000 ]; then
  head -c 60000 "$report" >"$work/cut.md"
  printf '\n\n…truncated, see the [run](%s).\n' "$RUN_URL" >>"$work/cut.md"
  mv "$work/cut.md" "$report"
fi
cat "$report" >>"${GITHUB_STEP_SUMMARY:-/dev/null}"
[ "$reports" = false ] || python3 -I "$here/junit-failures.py" --durations "$work/reports" >>"${GITHUB_STEP_SUMMARY:-/dev/null}"

# One issue per distinct failure (auto-found.py/.sh) instead of one per workflow; the structured results
# are the JUnit XML, the k6 summaries and the failed jobs. The "Nightly failure" issue of an older run stays
# until a green run closes it (above).
gh api "repos/$REPO/actions/runs/$RUN_ID/jobs?per_page=100" --paginate | jq -s '{jobs: map(.jobs) | add}' >"$work/jobs.json"
python3 -I "$here/auto-found.py" "$WORKFLOW" --jobs "$work/jobs.json" --junit "$work/reports" --k6 "$work/perf" >"$work/failures.json"
"$here/auto-found.sh" "$work/failures.json"
