#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Tracks flaky tests (failed, then passed on retry in a scheduled CI run) as GitHub issues, one open
# `flaky-test` issue per test method:
#   flaky this run, no open issue  -> open one (Bug type; on the project as a P2 "Flaky test");
#   flaky this run, issue open     -> comment with this run's failure;
#   not flaky, run green, issue untouched for 7 days -> close it as completed.
# Closing only on green runs means a run that never reached the tests cannot close anything.
#
# Usage: flaky-tests.sh <reports dir>   (the downloaded build-reports-* artifacts)
# Env: GH_TOKEN, REPO, RUN_URL, CONCLUSION, HEAD_SHA; optional PROJECT_* (see project-lib.sh)
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=project-lib.sh
. "$here/project-lib.sh"
reports=${1:?usage: flaky-tests.sh <reports dir>}
label="flaky-test"
quiet_days=7
sha=${HEAD_SHA:0:7}
module_labels=" boot build cli core mcp messaging plugin-api plugins plus persistence-postgres workers ui web "

flaky=$(python3 -I "$here/junit-failures.py" --flaky-json "$reports")
open=$(gh issue list -R "$REPO" --label "$label" --state open --limit 500 --json number,body,updatedAt)

marker() { printf '<!-- flaky-test: %s -->' "$1"; }

while IFS= read -r test; do
  [ -n "$test" ] || continue
  key=$(jq -r '"\(.class)#\(.name)"' <<<"$test")
  module=$(jq -r '.module // ""' <<<"$test")
  [ "$module" != build-logic ] || module=build
  report=$(jq -r --arg run "$RUN_URL" --arg sha "$sha" \
    '"Failed, then passed on retry on `\($sha)` ([run](\($run))):\n\n```text\n\(.detail)\n```"' <<<"$test")
  issue=$(jq -r --arg m "$(marker "$key")" 'map(select(.body | contains($m))) | first | .number // empty' <<<"$open")
  if [ -n "$issue" ]; then
    gh issue comment "$issue" -R "$REPO" --body "$report" >/dev/null
    echo "Flaky again: #$issue $key"
    continue
  fi
  labels=$label
  [[ $module_labels != *" $module "* ]] || labels+=",$module"
  # shellcheck disable=SC2016 # the backticks are Markdown
  body=$(printf '`%s` failed and then passed on retry in a scheduled CI run. A test that does that hides real failures and costs retries: make it deterministic, or find the race it exposes.\n\n%s\n\nThis issue gets a comment each time the test is flaky again, and closes itself after %s days without a flaky run.\n\n%s' \
    "$key" "$report" "$quiet_days" "$(marker "$key")")
  title="Flaky test: $(jq -r '"\(.class | split(".") | last).\(.name)"' <<<"$test")"
  url=$(gh issue create -R "$REPO" --title "$title" --label "$labels" --body "$body")
  echo "Opened $url for $key"
  issue_set_type "$REPO" "${url##*/}" Bug
  if project_enabled; then
    project_add_item "$url" Status=Triage Kind="Flaky test" Priority=P2 Module="$module" >/dev/null ||
      echo "warning: could not put $url on the project" >&2
  fi
done < <(jq -c '.[]' <<<"$flaky")

[ "$CONCLUSION" = success ] || exit 0
cutoff=$(date -u -d "-$quiet_days days" +%Y-%m-%dT%H:%M:%SZ)
flaky_keys=$(jq -c '[.[] | "\(.class)#\(.name)"]' <<<"$flaky")
jq -r --arg cutoff "$cutoff" --argjson keys "$flaky_keys" '
  .[] | select(.updatedAt < $cutoff)
  | (.body | capture("<!-- flaky-test: (?<k>.+?) -->").k // "") as $k
  | select($k != "" and ($keys | index($k) | not)) | .number' <<<"$open" |
  while read -r issue; do
    gh issue close "$issue" -R "$REPO" --reason completed \
      --comment "No flaky run for $quiet_days days (last checked on \`$sha\`, [run]($RUN_URL)). Closing; a new flaky run opens a new issue." >/dev/null
    echo "Closed #$issue: not flaky for $quiet_days days"
  done
