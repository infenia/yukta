#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Table test for project-lib.sh, issue-triage.py, flaky-tests.sh, released.sh (against a stub gh that
# records its calls) and project-health.py (on fixtures).
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

# --- flaky-tests.sh against a stub gh ----------------------------------------------------------------
# Reports: LlmTest.retries() is flaky (known issue #7), WTest.lease [1] is flaky (new);
# open issues: #7 (LlmTest, flaky again), #8 (quiet for weeks: close on green), #9 (recent: keep).
reports="$work/reports"
mkdir -p "$reports/build-reports-plugins/plugins/processors/llm-call/build/test-results/test" \
  "$reports/build-reports-infra/plus/workers/build/test-results/test"
cat >"$reports/build-reports-plugins/plugins/processors/llm-call/build/test-results/test/TEST-a.xml" <<'XML'
<testsuite name="com.x.LlmTest"><testcase classname="com.x.LlmTest" name="retries()"><failure message="boom"/></testcase><testcase classname="com.x.LlmTest" name="retries()"/></testsuite>
XML
cat >"$reports/build-reports-infra/plus/workers/build/test-results/test/TEST-b.xml" <<'XML'
<testsuite name="com.y.WTest"><testcase classname="com.y.WTest" name="lease [1]"><error message="timeout"/></testcase><testcase classname="com.y.WTest" name="lease [1]"/></testsuite>
XML
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "$*" >>"$GH_LOG"
case "$1 $2" in
  "issue list") jq -n --arg old 2020-01-01T00:00:00Z --arg new "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '[
    {number: 7, updatedAt: $old, body: "x <!-- flaky-test: com.x.LlmTest#retries() -->"},
    {number: 8, updatedAt: $old, body: "x <!-- flaky-test: com.z.Old#gone() -->"},
    {number: 9, updatedAt: $new, body: "x <!-- flaky-test: com.z.Recent#r() -->"}]' ;;
  "issue create") echo "https://github.com/o/r/issues/10" ;;
esac
STUB
for conclusion in failure success; do
  : >"$GH_LOG"
  REPO=o/r RUN_URL=u CONCLUSION=$conclusion HEAD_SHA=abcdef12 PROJECT_TOKEN='' \
    "$here/flaky-tests.sh" "$reports" >/dev/null
  expect_eq "flaky ($conclusion): comment on the known issue" "issue comment 7 -R o/r --body" \
    "$(grep -o '^issue comment 7 -R o/r --body' "$GH_LOG")"
  expect_eq "flaky ($conclusion): open one issue for the new test, with its module label" \
    "issue create -R o/r --title Flaky test: WTest.lease [1] --label flaky-test,workers" \
    "$(grep '^issue create' "$GH_LOG" | sed 's/ --body.*//')"
done
expect_eq "flaky (success): close only the quiet issue" "issue close 8" "$(grep -o '^issue close [0-9]*' "$GH_LOG")"
: >"$GH_LOG"
REPO=o/r RUN_URL=u CONCLUSION=failure HEAD_SHA=abcdef12 PROJECT_TOKEN='' "$here/flaky-tests.sh" "$reports" >/dev/null
expect_eq "flaky (failure): never close" "" "$(grep -o '^issue close' "$GH_LOG" || true)"

# --- released.sh against a stub gh ------------------------------------------------------------------
# v1.1 follows v1.0; commits a and b are in PRs 5 and 6 (b in both 5 and 6); PR 5 closes #40, PR 6
# closes #40 and #41; milestone v1.1 is open.
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "$*" >>"$GH_LOG"
case "$*" in
  "api repos/o/r/releases?per_page=100")
    echo '[{"tag_name":"v1.1","draft":false,"published_at":"2026-02-01"},{"tag_name":"v1.0","draft":false,"published_at":"2026-01-01"},{"tag_name":"v1.2","draft":true,"published_at":null}]' ;;
  *compare/v1.0...v1.1*) printf 'a\nb\n' ;;
  *commits/a/pulls*) echo 5 ;;
  *commits/b/pulls*) printf '5\n6\n' ;;
  *"pr=5"*) echo 40 ;;
  *"pr=6"*) printf '40\n41\n' ;;
  "api repos/o/r/milestones?state=open&per_page=100") echo '[{"number":3,"title":"v1.1"}]' ;;
esac
STUB
: >"$GH_LOG"
REPO=o/r TAG=v1.1 RELEASE_URL=u "$here/released.sh" >/dev/null
expect_eq "released: one comment per closed issue" "issue comment 40
issue comment 41" "$(grep -o '^issue comment [0-9]*' "$GH_LOG")"
expect_eq "released: compare with the previous published release" "1" "$(grep -c 'compare/v1.0...v1.1' "$GH_LOG")"
expect_eq "released: close the milestone" "api -X PATCH repos/o/r/milestones/3 -f state=closed" \
  "$(grep 'milestones/3' "$GH_LOG")"

# --- project-health.py on fixtures -------------------------------------------------------------------
health="$work/health"
mkdir -p "$health"
issue() { # number, title, created, updated, labels json, type, assignees json
  jq -n --argjson n "$1" --arg t "$2" --arg c "$3" --arg u "$4" --argjson l "$5" --arg ty "$6" --argjson a "$7" \
    '{number: $n, title: $t, html_url: "https://github.com/o/r/issues/\($n)", created_at: $c, updated_at: $u,
      labels: ($l | map({name: .})), type: (if $ty == "" then null else {name: $ty} end), assignees: $a}'
}
{
  echo '['
  issue 1 "Old untriaged" 2026-01-01T00:00:00Z 2026-01-01T00:00:00Z '["needs-triage"]' Bug '[]'; echo ,
  issue 2 "New untriaged" 2026-03-09T00:00:00Z 2026-03-09T00:00:00Z '["needs-triage"]' Bug '[]'; echo ,
  issue 3 "Big risk" 2026-01-01T00:00:00Z 2026-03-01T00:00:00Z '["risk","core"]' Task '[]'; echo ,
  issue 4 "Stuck work" 2026-01-01T00:00:00Z 2026-02-01T00:00:00Z '[]' Feature '[{"login":"a"}]'; echo ,
  echo '{"number": 5, "title": "A PR", "pull_request": {}, "labels": [], "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-01T00:00:00Z", "html_url": "x"}'
  echo ']'
} | jq -s . >"$health/issues.json"
echo '{"items": [
  {"id": "a", "status": "Backlog", "priority": "P1", "impact": 5, "effort": 2, "content": {"url": "https://github.com/o/r/issues/3"}},
  {"id": "b", "status": "In progress", "priority": "P2", "content": {"url": "https://github.com/o/r/issues/4"}}]}' >"$health/project.json"
echo '[[{"rule": {"security_severity_level": "high"}, "tool": {"name": "CodeQL"}},
  {"rule": {"security_severity_level": null}, "tool": {"name": "Scorecard"}}]]' >"$health/alerts.json"
report=$(python3 -I "$here/project-health.py" "$health" --repo o/r --now 2026-03-10T00:00:00Z)
expect_eq "health: counts issues, not pull requests" "4 open in all, 1 of them risks." \
  "$(grep '^[0-9]* open in all' <<<"$report")"
expect_eq "health: only the old untriaged issue" "- [#1](https://github.com/o/r/issues/1) Old untriaged — 68 days" \
  "$(sed -n '/^## Waiting for triage/,/^## /p' <<<"$report" | grep '^- ')"
expect_eq "health: unassigned P1" "- [#3](https://github.com/o/r/issues/3) Big risk — P1" \
  "$(sed -n '/^## P0 and P1/,/^## /p' <<<"$report" | grep '^- ')"
expect_eq "health: idle in progress" "- [#4](https://github.com/o/r/issues/4) Stuck work — 37 days" \
  "$(sed -n '/^## In progress/,/^## /p' <<<"$report" | grep '^- ')"
expect_eq "health: high-impact risk" "- [#3](https://github.com/o/r/issues/3) Big risk — impact 5, effort 2" \
  "$(sed -n '/^## Open risks/,/^## /p' <<<"$report" | grep '^- ')"
expect_eq "health: alerts are counted, not listed" \
  "2 open alerts: 0 critical, 1 high, 0 medium, 0 low, 1 without a security rating." \
  "$(grep '^[0-9]* open alerts' <<<"$report")"
rm "$health/project.json"
echo null >"$health/alerts.json"
report=$(python3 -I "$here/project-health.py" "$health" --repo o/r --now 2026-03-10T00:00:00Z)
expect_eq "health: works without the project and alerts" "2" \
  "$(grep -c -e 'project was not readable' -e 'Alerts were not readable' <<<"$report")"

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "project-lib.test.sh: all checks passed"
