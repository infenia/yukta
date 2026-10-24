#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Weekly project health report: fetches the open issues, the project items (with the App token, when
# configured) and the open code scanning alerts, renders them with project-health.py and writes the
# result into one pinned "Project health" issue, edited in place so watchers are not notified weekly.
#
# Env: GH_TOKEN (issues: write, security-events: read), REPO; optional PROJECT_* (see project-lib.sh)
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=project-lib.sh
. "$here/project-lib.sh"
title="Project health"
data=$(mktemp -d)
trap 'rm -rf "$data"' EXIT

gh api --paginate --slurp "repos/$REPO/issues?state=open&per_page=100" >"$data/issues.json"
if project_enabled; then
  _project_gh project item-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --limit 5000 --format json \
    >"$data/project.json" || rm -f "$data/project.json"
fi
gh api --paginate --slurp "repos/$REPO/code-scanning/alerts?state=open&per_page=100" >"$data/alerts.json" 2>/dev/null ||
  echo null >"$data/alerts.json"

# The last scheduled run of each job on main: a run, "none" (never ran) or "missing" (no such workflow).
workflows=$(python3 -I "$here/project-health.py" --workflows)
{
  for file in $workflows; do
    if run=$(gh api "repos/$REPO/actions/workflows/$file/runs?event=schedule&branch=main&per_page=1" 2>/dev/null); then
      jq -c --arg f "$file" '{($f): (.workflow_runs[0] // "none" | if type == "object" then {conclusion, created_at, html_url} else . end)}' <<<"$run"
    else
      jq -nc --arg f "$file" '{($f): "missing"}'
    fi
  done
} | jq -s 'add // {}' >"$data/runs.json"

python3 -I "$here/project-health.py" "$data" --repo "$REPO" >"$data/report.md"
cat "$data/report.md" >>"${GITHUB_STEP_SUMMARY:-/dev/null}"

issue=$(jq -r --arg t "$title" '[.[][] | select(.pull_request | not) | select(.title == $t)
  | select(.body // "" | contains("<!-- project-health -->"))] | first | .number // empty' "$data/issues.json")
if [ -n "$issue" ]; then
  gh issue edit "$issue" -R "$REPO" --body-file "$data/report.md" >/dev/null
  echo "Updated #$issue"
else
  url=$(gh issue create -R "$REPO" --title "$title" --label pinned --body-file "$data/report.md")
  gh issue pin "${url##*/}" -R "$REPO" >/dev/null 2>&1 || true
  echo "Opened $url"
fi
