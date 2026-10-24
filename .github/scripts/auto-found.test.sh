#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Table test for auto-found.sh and junit-failures.py --failed-json, against a stub gh that records its
# calls. A wrong answer here files issues twice, loses a blocker, or closes another workflow's issue.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0

expect_eq() {
  if [ "$2" != "$3" ]; then
    echo "FAIL $1"
    echo "  want: '$2'"
    echo "  got:  '$3'"
    failures=$((failures + 1))
  fi
}

hash() { printf '%s' "$1" | sha1sum | cut -c1-12; }

# --- the reports: CoreTest.broken, WorkflowAcceptanceTest.run, ApiKeyFilterTest.reject never pass;
# --- LlmTest.retries failed and passed on retry (flaky: not this script's business).
reports="$work/reports"
mkdir -p "$reports/build-reports-platform/core/build/test-results/test" \
  "$reports/build-reports-platform/boot/build/test-results/test"
cat >"$reports/build-reports-platform/core/build/test-results/test/TEST-a.xml" <<'XML'
<testsuite name="x"><testcase classname="com.x.CoreTest" name="broken()"><failure message="expected 1"/></testcase>
<testcase classname="com.x.LlmTest" name="retries()"><failure message="boom"/></testcase><testcase classname="com.x.LlmTest" name="retries()"/>
<testcase classname="com.x.Fine" name="ok()"/></testsuite>
XML
cat >"$reports/build-reports-platform/boot/build/test-results/test/TEST-b.xml" <<'XML'
<testsuite name="y"><testcase classname="com.y.WorkflowAcceptanceTest" name="run()"><failure message="seed=77 edges: t-&gt;n0"/></testcase>
<testcase classname="com.y.ApiKeyFilterTest" name="reject()"><error message="open"/></testcase>
<testcase classname="com.y.SlowTest" name="stuck()"><failure message="STUCK 3 runs"/></testcase></testsuite>
XML

# --- junit-failures.py --failed-json
failed=$(python3 -I "$here/junit-failures.py" --failed-json "$reports")
expect_eq "failed-json: broken tests only, flaky and passing ones left out" \
  "com.x.CoreTest#broken() com.y.ApiKeyFilterTest#reject() com.y.SlowTest#stuck() com.y.WorkflowAcceptanceTest#run()" \
  "$(jq -r 'map(.key) | sort | join(" ")' <<<"$failed")"
expect_eq "severity: stuck run and security check are Critical, acceptance High, the rest Medium" \
  "Medium Critical Critical High" \
  "$(jq -r 'sort_by(.key) | map(.severity) | join(" ")' <<<"$failed")"
expect_eq "module comes from the report path" "boot core" \
  "$(jq -r 'map(select(.key | test("CoreTest|WorkflowAcceptance")) | .module) | sort | join(" ")' <<<"$failed")"

expect_eq "repro: a simple module runs its own task, a plugin runs the class everywhere" \
  "./gradlew :core:test --tests 'com.x.CoreTest'|./gradlew :boot:test --tests 'com.y.WorkflowAcceptanceTest'" \
  "$(jq -r 'map(select(.key | test("CoreTest|WorkflowAcceptance"))) | sort_by(.key) | map(.repro) | join("|")' <<<"$failed")"
expect_eq "repro: a module outside the simple list" "./gradlew test --tests 'com.p.P'" \
  "$(python3 -I -c "
import importlib.util,sys
spec=importlib.util.spec_from_file_location('jf','$here/junit-failures.py'); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
print(m.repro_of('com.p.P','plugins'))")"

# --- auto-found.sh
mkdir -p "$work/bin"
export WORKFLOW="Java CI with Gradle"
old=2020-01-01T00:00:00Z
new=$(date -u +%Y-%m-%dT%H:%M:%SZ)
jq -n --arg old "$old" --arg new "$new" \
  --arg a "<!-- auto-found: $(hash 'com.x.CoreTest#broken()') workflow=$WORKFLOW -->" \
  --arg c "<!-- auto-found: $(hash 'com.y.ApiKeyFilterTest#reject()') workflow=$WORKFLOW -->" \
  --arg gone "<!-- auto-found: $(hash 'com.z.Gone#g()') workflow=$WORKFLOW -->" \
  --arg other "<!-- auto-found: $(hash 'com.z.Other#o()') workflow=Security Scanning -->" \
  --arg recent "<!-- auto-found: $(hash 'com.z.Recent#r()') workflow=$WORKFLOW -->" '[
  {number: 20, state: "OPEN", updatedAt: $old, body: ("x " + $a)},
  {number: 22, state: "CLOSED", updatedAt: $old, body: ("x " + $c)},
  {number: 23, state: "OPEN", updatedAt: $old, body: ("x " + $gone)},
  {number: 24, state: "OPEN", updatedAt: $old, body: ("x " + $other)},
  {number: 25, state: "OPEN", updatedAt: $new, body: ("x " + $recent)}]' >"$work/issues.json"
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "$*" >>"$GH_LOG"
case "$1 $2" in
  "issue list") cat "$ISSUES" ;;
  "issue create") echo "https://github.com/o/r/issues/30" ;;
esac
STUB
chmod +x "$work/bin/gh"
export GH_LOG="$work/gh.log" ISSUES="$work/issues.json" PATH="$work/bin:$PATH"

run() { : >"$GH_LOG"; REPO=o/r RUN_URL=u CONCLUSION=$1 HEAD_SHA=abcdef12 PROJECT_TOKEN='' "$here/auto-found.sh" <<<"$2" >/dev/null; }

run failure "$failed"
expect_eq "an open issue gets a comment, not a second issue" "issue comment 20 -R o/r --body" \
  "$(grep -o '^issue comment 20 -R o/r --body' "$GH_LOG")"
expect_eq "a closed issue is reopened and commented" "issue reopen 22 -R o/r|issue comment 22 -R o/r" \
  "$(grep -E '^issue (reopen|comment) 22' "$GH_LOG" | sed 's/ --body.*//; s/ >.*//' | paste -sd'|')"
expect_eq "new findings open one issue each, with auto-found and the module label" \
  "issue create -R o/r --title Test failure: SlowTest.stuck() --label auto-found,boot|issue create -R o/r --title Test failure: WorkflowAcceptanceTest.run() --label auto-found,boot" \
  "$(grep '^issue create' "$GH_LOG" | sed 's/ --body.*//' | sort | paste -sd'|')"
expect_eq "a failing run never closes" "" "$(grep -o '^issue close' "$GH_LOG" || true)"

run success "[]"
expect_eq "a green run closes this workflow's quiet issues whose finding is gone, not another's or a recent one" "issue close 20|issue close 23" \
  "$(grep -o '^issue close [0-9]*' "$GH_LOG" | paste -sd'|')"

run success "$(jq -c '[.[] | select(.key | test("CoreTest"))]' <<<"$failed")"
expect_eq "a finding that is still there is not closed" "issue close 23" \
  "$(grep -o '^issue close [0-9]*' "$GH_LOG" | paste -sd'|')"

: >"$GH_LOG"
REPO=o/r RUN_URL=u CONCLUSION=success HEAD_SHA=abcdef12 PROJECT_TOKEN='' "$here/auto-found.sh" </dev/null >/dev/null
expect_eq "empty input is the same as no findings" "issue close 20|issue close 23" "$(grep -o '^issue close [0-9]*' "$GH_LOG" | paste -sd'|')"

if [ "$failures" -gt 0 ]; then
  echo "$failures auto-found check(s) failed"
  exit 1
fi
echo "auto-found.sh: all checks passed"
