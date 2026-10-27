#!/usr/bin/env bash
# File a Yukta defect found by the dogfood loop as a GitHub issue (the "Dogfood finding" form, label
# `dogfood`). If an open dogfood issue already has the same title, the new evidence is added to it as a
# comment instead, so repeated runs do not file duplicates. The issue-triage workflow then puts it on
# the Yukta Roadmap project with its module and priority.
#
# Usage: report-finding.sh --title T --module M --priority P0|P1|P2|P3 --evidence E [--kind K]
#                          [--workflow W] [--execution X] [--repro R] [--expected X] [--dry-run]
#   --module    boot build cli core mcp messaging plugin-api plugins plus persistence-postgres workers ui web
#               documentation (dogfood scripts themselves: build)
#   --kind      bug (default) | error | perf | ux | security | improvement
#   --evidence  the ERROR / WARN / CONTROL / SLOW / RESULT line
# Env: YUKTA_ISSUES_REPO (default infenia/yukta); gh must be logged in.
set -euo pipefail

repo=${YUKTA_ISSUES_REPO:-infenia/yukta}
title="" module="" priority="" evidence="" kind=bug workflow="" execution="" repro="" expected="" dry=0
usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --title) title=$2; shift 2 ;;
    --module) module=$2; shift 2 ;;
    --priority) priority=$2; shift 2 ;;
    --evidence) evidence=$2; shift 2 ;;
    --kind) kind=$2; shift 2 ;;
    --workflow) workflow=$2; shift 2 ;;
    --execution) execution=$2; shift 2 ;;
    --repro) repro=$2; shift 2 ;;
    --expected) expected=$2; shift 2 ;;
    --dry-run) dry=1; shift ;;
    *) usage ;;
  esac
done
[[ -n $title && -n $module && -n $evidence ]] || usage
modules=" boot build cli core mcp messaging plugin-api plugins plus persistence-postgres workers ui web documentation "
[[ $modules == *" $module "* ]] || { echo "report-finding.sh: unknown module '$module'" >&2; exit 2; }
[[ $priority =~ ^P[0-3]$ ]] || { echo "report-finding.sh: --priority must be P0..P3" >&2; exit 2; }

labels=dogfood
case "$kind" in
  perf) labels+=,perf ;;
  security) labels+=,security ;;
  bug | error | ux | improvement) ;;
  *) echo "report-finding.sh: unknown kind '$kind'" >&2; exit 2 ;;
esac
type=Bug
[[ $kind != improvement ]] || type=Task
commit=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)

# The same "### <label>" sections as .github/ISSUE_TEMPLATE/dogfood.yml, so triage reads them alike.
body=$(cat <<EOF
### Workflow and execution

${workflow:-_No response_}${execution:+, execution \`$execution\`}

### Observed vs. expected

Observed (\`$kind\`):

\`\`\`text
$evidence
\`\`\`
${expected:+
Expected: $expected}

### How to reproduce

${repro:-not reproduced yet}

### Module

$module

### Priority

$priority

### Commit

\`$commit\`
EOF
)

if [[ $dry -eq 1 ]]; then
  printf 'repo: %s\ntitle: %s\nlabels: %s\ntype: %s\n\n%s\n' "$repo" "$title" "$labels" "$type" "$body"
  exit 0
fi

existing=$(gh issue list -R "$repo" --label dogfood --state open --limit 200 --json number,title |
  jq -r --arg t "$title" 'map(select(.title == $t)) | first | .number // empty')
if [[ -n $existing ]]; then
  gh issue comment "$existing" -R "$repo" --body "Seen again on \`$commit\`:

$body" >/dev/null
  echo "UPDATED https://github.com/$repo/issues/$existing"
  exit 0
fi

url=$(gh issue create -R "$repo" --title "$title" --label "$labels" --body "$body")
gh api -X PATCH "repos/$repo/issues/${url##*/}" -f type="$type" >/dev/null ||
  echo "report-finding.sh: could not set the issue type to $type" >&2
echo "FILED $url"
