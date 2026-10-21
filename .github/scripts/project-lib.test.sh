#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Table test for project-lib.sh and issue-triage.py, against a stub gh that records its calls.
# A wrong answer here files issues in the wrong place or with wrong fields, silently.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
failures=0
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

fail() {
  echo "FAIL $1"
  echo "  want: '$2'"
  echo "  got:  '$3'"
  failures=$((failures + 1))
}

expect_eq() {
  [ "$2" = "$3" ] || fail "$1" "$2" "$3"
}

# --- project-lib.sh against a stub gh --------------------------------------------------------------
mkdir -p "$work/bin"
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "$*" >>"$GH_LOG"
case "$1 $2" in
  "project view") echo '{"id":"PVT_1"}' ;;
  "project field-list")
    echo '{"fields":[
      {"id":"F_STATUS","name":"Status","type":"ProjectV2SingleSelectField","options":[{"id":"o_triage","name":"Triage"}]},
      {"id":"F_KIND","name":"Kind","type":"ProjectV2SingleSelectField","options":[{"id":"o_ci","name":"CI failure"}]},
      {"id":"F_IMPACT","name":"Impact","type":"ProjectV2Field"},
      {"id":"F_LEGACY","name":"Legacy ID","type":"ProjectV2Field"}]}' ;;
  "project item-add") echo '{"id":"ITEM_1"}' ;;
  *) echo '{}' ;;
esac
STUB
chmod +x "$work/bin/gh"
export GH_LOG="$work/gh.log" PATH="$work/bin:$PATH"

(
  unset PROJECT_TOKEN
  # shellcheck source=project-lib.sh
  . "$here/project-lib.sh"
  if project_enabled; then
    echo "FAIL project_enabled is true without a token"
    exit 1
  fi
) || failures=$((failures + 1))

: >"$GH_LOG"
item=$(
  PROJECT_OWNER=infenia PROJECT_NUMBER=7 PROJECT_TOKEN=t
  # shellcheck source=project-lib.sh
  . "$here/project-lib.sh"
  project_add_item https://github.com/o/r/issues/5 Status=Triage Kind="CI failure" Impact=4 \
    "Legacy ID=G1" Kind=Nope Missing=x Priority= 2>"$work/warnings"
)
expect_eq "project_add_item prints the item id" "ITEM_1" "$item"
expect_eq "item-add uses the project and url" \
  "project item-add 7 --owner infenia --url https://github.com/o/r/issues/5 --format json" \
  "$(grep '^project item-add' "$GH_LOG")"
expect_eq "single select, number and text fields" \
  "--field-id F_STATUS --single-select-option-id o_triage
--field-id F_KIND --single-select-option-id o_ci
--field-id F_IMPACT --number 4
--field-id F_LEGACY --text G1" \
  "$(grep '^project item-edit' "$GH_LOG" | sed 's/.*PVT_1 //')"
expect_eq "unknown option and field are reported, empty values skipped" \
  'project: field "Kind" has no option "Nope"; skipped
project: no field "Missing"; skipped' "$(cat "$work/warnings")"

# --- issue-triage.py ---------------------------------------------------------------------------------
# triage <labels json> <type or empty> <body> -> compact JSON
triage() {
  jq -n --argjson labels "$1" --arg type "$2" --arg body "$3" \
    '{issue: {labels: ($labels | map({name: .})), type: (if $type == "" then null else {name: $type} end), body: $body}}' \
    >"$work/event.json"
  python3 -I "$here/issue-triage.py" "$work/event.json" | jq -c .
}

bug_body=$'### What happened?\n\nIt broke\n\n### Module\n\nbuild (build logic, Gradle, CI)\n\n### Severity\n\nHigh (a feature is broken, no workaround)\n\n### Environment\n\n_No response_'
expect_eq "bug form" \
  '{"labels":["build"],"fields":["Status=Triage","Module=build","Severity=High","Kind=Defect"]}' \
  "$(triage '["needs-triage"]' Bug "$bug_body")"

risk_body=$'### Risk\n\nx\n\n### Module\n\ncore\n\n### Category\n\nSecurity\n\n### Impact\n\n4\n\n### Effort\n\n2'
expect_eq "risk form adds security for a security category" \
  '{"labels":["core","security"],"fields":["Status=Triage","Module=core","Category=Security","Impact=4","Effort=2","Kind=Risk"]}' \
  "$(triage '["risk","needs-triage"]' Task "$risk_body")"

expect_eq "dogfood priority, area label already there" \
  '{"labels":[],"fields":["Status=Triage","Module=ui","Priority=P0","Kind=Dogfood"]}' \
  "$(triage '["dogfood","ui"]' Bug $'### Module\n\nui\n\n### Priority\n\nP0 (fix now)')"

expect_eq "answers outside the option lists are ignored" \
  '{"labels":[],"fields":["Status=Triage","Kind=Improvement"]}' \
  "$(triage '[]' Feature $'### Module\n\nnot sure\n\n### Severity\n\n$(rm -rf /)\n\n### Impact\n\n9')"

expect_eq "issue without a form" \
  '{"labels":[],"fields":["Status=Triage","Kind=CI failure"]}' \
  "$(triage '["ci-nightly"]' "" "The nightly run failed.")"

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "project-lib.test.sh: all checks passed"
