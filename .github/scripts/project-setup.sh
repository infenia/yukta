#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Creates the "Yukta Roadmap" org project and its custom fields (see .github/PROJECT.md), and links
# it to the repository. Idempotent: what exists is left alone; a field whose options differ from
# the list below is reported, not changed, so hand edits in the project UI are never overwritten.
# Views, the Status options, the Iteration field and the built-in workflows have no API (or no gh
# command); the script prints what is left to do by hand.
#
# Usage: project-setup.sh [--dry-run] <owner> [repo] [title]
#   e.g. project-setup.sh infenia yukta
# Needs: gh logged in with the project and repo scopes (gh auth refresh -s project), and jq.
set -euo pipefail

dry_run=false
if [ "${1:-}" = --dry-run ]; then
  dry_run=true
  shift
fi
owner=${1:?usage: project-setup.sh [--dry-run] <owner> [repo] [title]}
repo=${2:-yukta}
title=${3:-Yukta Roadmap}

# name|type|options (comma separated, single select only). Keep in line with .github/PROJECT.md.
fields=(
  "Priority|SINGLE_SELECT|P0,P1,P2,P3"
  "Severity|SINGLE_SELECT|Critical,High,Medium,Low"
  "Module|SINGLE_SELECT|boot,build,cli,core,mcp,messaging,plugin-api,plugins,plus,persistence-postgres,workers,ui,web,documentation"
  "Kind|SINGLE_SELECT|Defect,Risk,Improvement,Hardening,Dogfood,CI failure,Flaky test,Perf"
  "Category|SINGLE_SELECT|Security,Correctness,Reliability,Availability,Performance,Scalability,Evolvability,Operability,Governance"
  "Impact|NUMBER|"
  "Effort|NUMBER|"
  "Legacy ID|TEXT|"
  "Start date|DATE|"
  "Target date|DATE|"
)
status_options="Triage,Backlog,Ready,In progress,In review,Done"

run() {
  if [ "$dry_run" = true ]; then
    echo "would run: $*"
  else
    "$@"
  fi
}

number=$(gh project list --owner "$owner" --limit 100 --format json |
  jq -r --arg t "$title" '.projects | map(select(.title == $t)) | first | .number // empty')
if [ -z "$number" ]; then
  echo "Creating project \"$title\""
  if [ "$dry_run" = true ]; then
    echo "would run: gh project create --owner $owner --title \"$title\""
    exit 0
  fi
  number=$(gh project create --owner "$owner" --title "$title" --format json | jq -r .number)
fi
echo "Project \"$title\" is #$number"

# gh has no read for linked repositories; linking again fails harmlessly.
run gh project link "$number" --owner "$owner" --repo "$owner/$repo" >/dev/null 2>&1 &&
  echo "Linked to $owner/$repo" ||
  echo "Not linked to $owner/$repo (already linked, or no access): check the project settings"

existing=$(gh project field-list "$number" --owner "$owner" --limit 100 --format json)
todo=()

for spec in "${fields[@]}"; do
  IFS='|' read -r name type options <<<"$spec"
  field=$(jq -c --arg n "$name" '.fields | map(select(.name == $n)) | first // empty' <<<"$existing")
  if [ -z "$field" ]; then
    echo "Creating field $name ($type)"
    if [ "$type" = SINGLE_SELECT ]; then
      run gh project field-create "$number" --owner "$owner" --name "$name" --data-type "$type" \
        --single-select-options "$options" >/dev/null
    else
      run gh project field-create "$number" --owner "$owner" --name "$name" --data-type "$type" >/dev/null
    fi
    continue
  fi
  if [ "$type" = SINGLE_SELECT ]; then
    have=$(jq -r '[.options[]?.name] | join(",")' <<<"$field")
    [ "$have" = "$options" ] || todo+=("Field $name has options \"$have\"; expected \"$options\"")
  fi
  echo "Field $name exists"
done

status=$(jq -r '.fields | map(select(.name == "Status")) | first | [.options[]?.name] | join(",")' <<<"$existing")
[ "$status" = "$status_options" ] ||
  todo+=("Status has options \"$status\"; set them to \"$status_options\" (project settings → Status)")
jq -e '.fields | any(.type == "ProjectV2IterationField")' <<<"$existing" >/dev/null ||
  todo+=("Add an Iteration field \"Iteration\" (2 weeks) in the project settings")
todo+=("Create the views and turn on the built-in workflows listed in .github/PROJECT.md")

echo
echo "Left to do by hand in https://github.com/orgs/$owner/projects/$number:"
for item in "${todo[@]}"; do
  echo "  - $item"
done
