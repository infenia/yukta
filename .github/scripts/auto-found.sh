#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# One GitHub issue per distinct failure found by a scheduled run, not one per workflow.
#   auto-found.sh <failures.json>          from auto-found.py (which also holds the severity table)
#   auto-found.sh --green                  the run was green: count it for the open issues of $WORKFLOW
# For each failure the key (<!-- auto-found: <key> --> in the issue body) decides:
#   an open issue has it   -> comment with the run link and date, restart its green count;
#   only a closed one has it -> reopen it with the comment;
#   none                   -> create it: labels auto-found + module, Bug type, and on the project (when
#                             configured) in Triage with Kind, Severity, Priority (High P1, Medium P2, Low P3), Module.
# A green run adds one to the <!-- auto-found-green: N --> count of each open issue of the workflow; at
# GREEN_RUNS (3) in a row the issue is closed. A failing run of the workflow with other failures leaves
# it alone: only green runs count.
#
# Env: GH_TOKEN, REPO, RUN_URL, WORKFLOW, HEAD_SHA; optional PROJECT_TOKEN, PROJECT_OWNER, PROJECT_NUMBER, GREEN_RUNS
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=project-lib.sh
. "$here/project-lib.sh"
label=auto-found
green_runs=${GREEN_RUNS:-3}
sha=${HEAD_SHA:0:7}
today=$(date -u +%Y-%m-%d)
module_labels=" boot build cli core mcp messaging plugin-api plugins plus persistence-postgres workers ui web "

mode=failure
if [ "${1:-}" = --green ]; then
  mode=green
  shift
fi
input=${1:-}
[ "$mode" = green ] || [ -n "$input" ] || { echo "usage: auto-found.sh [--green] <failures.json>" >&2; exit 2; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

marker() { printf '<!-- %s: %s -->' "$1" "$2"; }
# The issues of this tool, open and closed; the body carries the markers.
issues() { gh issue list -R "$REPO" --label "$label" --state all --limit 1000 --json number,state,body; }
set_green() { # <issue> <body> <count>
  printf '%s' "$2" | sed -E "s/<!-- auto-found-green: [0-9]+ -->/<!-- auto-found-green: $3 -->/" >"$work/body.md"
  gh issue edit "$1" -R "$REPO" --body-file "$work/body.md" >/dev/null
}

if [ "$mode" = green ]; then
  jq -c --arg w "$(marker auto-found-workflow "$WORKFLOW")" \
    '.[] | select(.state == "OPEN" and (.body | contains($w)))' <<<"$(issues)" |
    while IFS= read -r issue; do
      number=$(jq -r .number <<<"$issue")
      body=$(jq -r .body <<<"$issue")
      n=$(($(grep -oE 'auto-found-green: [0-9]+' <<<"$body" | grep -oE '[0-9]+$' || echo 0) + 1))
      if [ "$n" -ge "$green_runs" ]; then
        gh issue close "$number" -R "$REPO" --reason completed \
          --comment "The **$WORKFLOW** run was green $green_runs times in a row (last on \`$sha\`, $today, [run]($RUN_URL)). Closing; a new failure reopens it." >/dev/null
        echo "Closed #$number: $n green runs"
      else
        set_green "$number" "$body" "$n"
        echo "Counted a green run for #$number ($n of $green_runs)"
      fi
    done
  exit 0
fi

existing=$(issues)
while IFS= read -r f; do
  [ -n "$f" ] || continue
  key=$(jq -r .key <<<"$f")
  m=$(marker auto-found "$key")
  hit=$(jq -c --arg m "$m" '[.[] | select(.body | contains($m))] | (map(select(.state == "OPEN")) + .) | first // empty' <<<"$existing")
  comment="Failed again on \`$sha\` ($today, [run]($RUN_URL)).

$(jq -r .summary <<<"$f")

Reproduce: $(jq -r .repro <<<"$f")"
  if [ -n "$hit" ]; then
    number=$(jq -r .number <<<"$hit")
    if [ "$(jq -r .state <<<"$hit")" = OPEN ]; then
      gh issue comment "$number" -R "$REPO" --body "$comment" >/dev/null
      echo "Failed again: #$number ($key)"
    else
      gh issue reopen "$number" -R "$REPO" --comment "$comment" >/dev/null
      echo "Reopened #$number ($key)"
    fi
    set_green "$number" "$(jq -r .body <<<"$hit")" 0
    continue
  fi
  module=$(jq -r .module <<<"$f")
  labels=$label
  [[ $module_labels != *" $module "* ]] || labels+=",$module"
  severity=$(jq -r .severity <<<"$f")
  {
    echo "A scheduled **$WORKFLOW** run on \`main\` failed (\`$sha\`, $today, [run]($RUN_URL)). Found by the automation, once per distinct failure: a repeat adds a comment, and the issue closes after $green_runs green runs in a row."
    echo
    echo "**Severity:** $severity"
    echo
    jq -r .summary <<<"$f"
    echo
    echo "**Reproduce:** $(jq -r .repro <<<"$f")"
    echo
    marker auto-found "$key"
    echo
    marker auto-found-workflow "$WORKFLOW"
    echo
    marker auto-found-green 0
  } >"$work/new.md"
  url=$(gh issue create -R "$REPO" --title "$(jq -r .title <<<"$f")" --label "$labels" --body-file "$work/new.md")
  echo "Opened $url ($key)"
  # Later failures of the same run must find this issue, so a repeat inside one run is one issue too.
  existing=$(jq -c --arg n "${url##*/}" --rawfile b "$work/new.md" '. + [{number: ($n | tonumber), state: "OPEN", body: $b}]' <<<"$existing")
  issue_set_type "$REPO" "${url##*/}" Bug
  if project_enabled; then
    case $severity in High) priority=P1 ;; Medium) priority=P2 ;; *) priority=P3 ;; esac
    project_add_item "$url" Status=Triage Kind="$(jq -r .kind <<<"$f")" Severity="$severity" Priority="$priority" Module="$module" >/dev/null ||
      echo "warning: could not put $url on the project" >&2
  fi
done < <(jq -c '.[]' "$input")
