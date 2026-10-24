#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Files what the scheduled CI jobs find as GitHub issues, one `auto-found` issue per distinct finding
# instead of one per workflow, so a blocker and a minor defect are not the same line in one issue:
#   finding, no issue yet      -> open one (Bug type; on the project in Triage with a Severity);
#   finding, issue open        -> comment with this run;
#   finding, issue closed      -> reopen it and comment (it came back);
#   green run, issue quiet for QUIET_DAYS (3) and the finding gone -> close it.
# A finding is identified by its `key` (for a test, class#method), hashed into a hidden marker in the
# issue body together with the workflow name, so two workflows never close each other's issues.
#
# Usage: <findings JSON> | auto-found.sh      a JSON array, "[]" when there is none:
#        [{"key": "com.x.T#m()", "title": "...", "severity": "Critical|High|Medium|Low",
#          "module": "core", "detail": "text for the issue", "repro": "command (optional)"}]
# Sources: junit-failures.py --failed-json (broken tests, with the seed of a property or fuzz test in
# the message). A job that measures something else (perf, mutation score) pipes its own array in.
# Severity: Critical = a stuck run, lost events, a failed security check; High = an acceptance, smoke
# or property test (the whole application); Medium = other tests; Low = noise. Critical and High are
# Priority P1, Medium P2, Low P3.
# Env: GH_TOKEN, REPO, WORKFLOW, RUN_URL, CONCLUSION, HEAD_SHA; optional PROJECT_* (see project-lib.sh)
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=project-lib.sh
. "$here/project-lib.sh"
label=auto-found
quiet_days=3
sha=${HEAD_SHA:0:7}
module_labels=" boot build cli core mcp messaging plugin-api plugins plus persistence-postgres workers ui web "

findings=$(cat)
[ -n "$findings" ] || findings='[]'
issues=$(gh issue list -R "$REPO" --label "$label" --state all --limit 500 --json number,state,body,updatedAt)

hash() { printf '%s' "$1" | sha1sum | cut -c1-12; }
marker() { printf '<!-- auto-found: %s workflow=%s -->' "$(hash "$1")" "$WORKFLOW"; }
priority() {
  case $1 in Critical | High) echo P1 ;; Low) echo P3 ;; *) echo P2 ;; esac
}

seen=''
while IFS= read -r finding; do
  [ -n "$finding" ] || continue
  key=$(jq -r .key <<<"$finding")
  title=$(jq -r .title <<<"$finding")
  severity=$(jq -r '.severity // "Medium"' <<<"$finding")
  module=$(jq -r '.module // ""' <<<"$finding")
  [ "$module" != build-logic ] || module=build
  seen+="$(hash "$key") "
  note=$(jq -r --arg run "$RUN_URL" --arg sha "$sha" --arg sev "$severity" \
    '"Found on `\($sha)` ([run](\($run))), severity **\($sev)**:\n\n```text\n\(.detail)\n```"
      + (if (.repro // "") != "" then "\n\nReproduce: `\(.repro)`" else "" end)' <<<"$finding")
  found=$(jq -c --arg m "$(marker "$key")" 'map(select(.body | contains($m))) | first // empty' <<<"$issues")
  if [ -n "$found" ]; then
    number=$(jq -r .number <<<"$found")
    if [ "$(jq -r .state <<<"$found")" = CLOSED ]; then
      gh issue reopen "$number" -R "$REPO" >/dev/null
      echo "Reopened #$number: $key"
    else
      echo "Again: #$number $key"
    fi
    gh issue comment "$number" -R "$REPO" --body "$note" >/dev/null
    continue
  fi
  labels=$label
  [[ $module_labels != *" $module "* ]] || labels+=",$module"
  # shellcheck disable=SC2016 # the backticks are Markdown
  body=$(printf 'Found by the scheduled **%s** run on `main`: `%s`.\n\n%s\n\nThis issue gets a comment each time the finding comes back and closes itself after %s days without it, on a green run. Reopened if it returns.\n\n%s' \
    "$WORKFLOW" "$key" "$note" "$quiet_days" "$(marker "$key")")
  url=$(gh issue create -R "$REPO" --title "$title" --label "$labels" --body "$body")
  echo "Opened $url for $key"
  issue_set_type "$REPO" "${url##*/}" Bug
  if project_enabled; then
    project_add_item "$url" Status=Triage Kind=Defect Priority="$(priority "$severity")" \
      Severity="$severity" Module="$module" >/dev/null ||
      echo "warning: could not put $url on the project" >&2
  fi
done < <(jq -c '.[]' <<<"$findings")

[ "$CONCLUSION" = success ] || exit 0
cutoff=$(date -u -d "-$quiet_days days" +%Y-%m-%dT%H:%M:%SZ)
jq -r --arg cutoff "$cutoff" --arg wf "workflow=$WORKFLOW -->" --arg seen "$seen" '
  .[] | select(.state == "OPEN" and .updatedAt < $cutoff and (.body | contains($wf)))
  | (.body | capture("<!-- auto-found: (?<h>[0-9a-f]+) ").h // "") as $h
  | select($h != "" and (($seen | split(" ")) | index($h) | not)) | .number' <<<"$issues" |
  while read -r number; do
    gh issue close "$number" -R "$REPO" --reason completed \
      --comment "Not found for $quiet_days days and **$WORKFLOW** is green (last checked on \`$sha\`, [run]($RUN_URL)). Closing; it is reopened if it comes back." >/dev/null
    echo "Closed #$number: gone for $quiet_days days"
  done
