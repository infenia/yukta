#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Tests auto-found.py (on JUnit and k6 fixtures) and auto-found.sh (against a stub gh that keeps issues in a
# file): the same failure twice is one issue, two distinct failures are two, a closed issue is reopened,
# and three green runs in a row close an issue. A wrong answer here files duplicates or hides a failure.
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
expect_eq() { [ "$2" = "$3" ] || fail "$1" "$2" "$3"; }

# --- fixtures ---------------------------------------------------------------------------------------
junit() { # <dir> <seed>: one failing property in core, one test that failed then passed (flaky)
  mkdir -p "$1/build-reports-core/core/build/test-results/test"
  cat >"$1/build-reports-core/core/build/test-results/test/TEST-a.xml" <<XML
<testsuite name="a" tests="3"><testcase classname="com.infenia.yukta.DagPropertyTest" name="runsEveryNode">
<failure message="Property failed, seed = $2" type="org.opentest4j.AssertionFailedError">trace</failure></testcase>
<testcase classname="com.infenia.yukta.FlakyTest" name="sometimes"><failure message="x" type="java.lang.IllegalStateException">t</failure></testcase>
<testcase classname="com.infenia.yukta.FlakyTest" name="sometimes"/>
</testsuite>
XML
}
mkdir -p "$work/k6/soak/run1"
cat >"$work/k6/soak/run1/summary.json" <<'JSON'
{"metrics":{"stuck_runs":{"count":3,"thresholds":{"count==0":true}},
 "workflow_success":{"value":0.9,"thresholds":{"rate>0.99":true}},
 "workflow_success{wf:mapper}":{"value":0.9,"thresholds":{"rate>0.99":true}},
 "workflow_e2e":{"p(95)":10,"thresholds":{"p(95)<2000":false}}}}
JSON
echo '{"test":"soak","commit":"abc1234","rate":"10","duration":"30m","hold":"default"}' >"$work/k6/soak/run1/meta.json"
cat >"$work/jobs.json" <<'JSON'
{"jobs":[{"name":"build","conclusion":"failure","html_url":"u","steps":[{"name":"Compile","conclusion":"failure"}]},
 {"name":"ok","conclusion":"success","steps":[]}]}
JSON

# --- auto-found.py ---------------------------------------------------------------------------------
junit "$work/j1" 111
junit "$work/j2" 222
a=$(python3 -I "$here/auto-found.py" Nightly --junit "$work/j1")
b=$(python3 -I "$here/auto-found.py" Nightly --junit "$work/j2")
expect_eq "a failing test is one failure, the flaky one is left to flaky-tests.sh" 1 "$(jq length <<<"$a")"
expect_eq "the key does not depend on the seed" "$(jq -r '.[0].key' <<<"$a")" "$(jq -r '.[0].key' <<<"$b")"
expect_eq "the seed is in the repro hint" true "$(jq -r '.[0].repro | contains("111")' <<<"$a")"
expect_eq "module comes from the report path" core "$(jq -r '.[0].module' <<<"$a")"
expect_eq "the key depends on the workflow" true \
  "$([ "$(jq -r '.[0].key' <<<"$a")" != "$(python3 -I "$here/auto-found.py" Other --junit "$work/j1" | jq -r '.[0].key')" ] && echo true || echo false)"

k=$(python3 -I "$here/auto-found.py" "Perf nightly" --k6 "$work/k6")
expect_eq "k6: one failure per breached base metric" "stuck_runs workflow_success" \
  "$(jq -r '[.[].title | capture("breaks (?<m>[a-z_]+)").m] | sort | join(" ")' <<<"$k")"
expect_eq "k6: stuck runs are High" High "$(jq -r '.[] | select(.title | contains("stuck_runs")) | .severity' <<<"$k")"
expect_eq "k6: a success rate is Medium" Medium "$(jq -r '.[] | select(.title | contains("workflow_success")) | .severity' <<<"$k")"
expect_eq "k6: kind is Perf" Perf "$(jq -r '.[0].kind' <<<"$k")"

jb=$(python3 -I "$here/auto-found.py" Nightly --jobs "$work/jobs.json")
expect_eq "a failed job with nothing more specific is listed" 1 "$(jq length <<<"$jb")"
expect_eq "a broken build is High" High "$(jq -r '.[0].severity' <<<"$jb")"
expect_eq "jobs are not listed when a test was found" 1 \
  "$(python3 -I "$here/auto-found.py" Nightly --junit "$work/j1" --jobs "$work/jobs.json" | jq length)"

# --- auto-found.sh against a stub gh ---------------------------------------------------------------
mkdir -p "$work/bin"
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
# Keeps the issues in $STATE (JSON array) and the calls in $GH_LOG.
echo "$*" >>"$GH_LOG"
cmd="$1 $2"; shift 2
number=""; body=""; title=""
case $cmd in "issue comment"|"issue reopen"|"issue close"|"issue edit") number=$1; shift ;; esac
while [ $# -gt 0 ]; do
  case $1 in
    --body) body=$2; shift ;;
    --body-file) body=$(cat "$2"); shift ;;
    --comment) body=$2; shift ;;
    --title) title=$2; shift ;;
    --label|--state|--limit|--json|-R|--reason) shift ;;
  esac
  shift
done
case $cmd in
  "issue list") cat "$STATE" ;;
  "issue create")
    n=$(($(jq length "$STATE") + 1))
    jq --arg t "$title" --arg b "$body" --argjson n "$n" '. + [{number: $n, state: "OPEN", title: $t, body: $b}]' "$STATE" >"$STATE.new"
    mv "$STATE.new" "$STATE"
    echo "https://github.com/o/r/issues/$n" ;;
  "issue reopen") jq --argjson n "$number" 'map(if .number == $n then .state = "OPEN" else . end)' "$STATE" >"$STATE.new"; mv "$STATE.new" "$STATE" ;;
  "issue close") jq --argjson n "$number" 'map(if .number == $n then .state = "CLOSED" else . end)' "$STATE" >"$STATE.new"; mv "$STATE.new" "$STATE" ;;
  "issue edit") jq --argjson n "$number" --arg b "$body" 'map(if .number == $n then .body = $b else . end)' "$STATE" >"$STATE.new"; mv "$STATE.new" "$STATE" ;;
  *) echo '{}' ;;
esac
STUB
chmod +x "$work/bin/gh"
export GH_LOG="$work/gh.log" STATE="$work/state.json" PATH="$work/bin:$PATH"
export REPO=o/r RUN_URL=https://example/run/1 WORKFLOW="Perf nightly" HEAD_SHA=0123456789abcdef GH_TOKEN=t
unset PROJECT_TOKEN PROJECT_OWNER PROJECT_NUMBER

count() { jq length "$STATE"; }
comments() { grep -c '^issue comment' "$GH_LOG" || true; }
two=$(jq -s 'add' <(jq '[.[0]]' <<<"$k") <(jq '[.[0]]' <<<"$jb"))   # two distinct failures

echo '[]' >"$STATE"; : >"$GH_LOG"
echo "$two" >"$work/two.json"
"$here/auto-found.sh" "$work/two.json" >/dev/null
expect_eq "two distinct failures give two issues" 2 "$(count)"
expect_eq "the issue carries the key marker" true "$(jq -r '.[0].body | contains("<!-- auto-found: ")' "$STATE")"
expect_eq "the issue carries the workflow marker" true "$(jq -r '.[0].body | contains("<!-- auto-found-workflow: Perf nightly -->")' "$STATE")"
expect_eq "the issue has severity and a repro hint" true "$(jq -r '.[0].body | contains("**Severity:**") and contains("**Reproduce:**")' "$STATE")"

: >"$GH_LOG"
"$here/auto-found.sh" "$work/two.json" >/dev/null
expect_eq "a repeat adds no issue" 2 "$(count)"
expect_eq "a repeat adds a comment per failure" 2 "$(comments)"

jq -s 'add' "$work/two.json" "$work/two.json" >"$work/dup.json"
echo '[]' >"$STATE"
"$here/auto-found.sh" "$work/dup.json" >/dev/null
expect_eq "the same failure twice in one run is one issue" 2 "$(count)"

# reopen: close the first issue by hand, then it fails again
jq 'map(if .number == 1 then .state = "CLOSED" else . end)' "$STATE" >"$STATE.new" && mv "$STATE.new" "$STATE"
: >"$GH_LOG"
"$here/auto-found.sh" "$work/two.json" >/dev/null
expect_eq "a closed issue is reopened, not duplicated" 2 "$(count)"
expect_eq "the reopened issue is open" OPEN "$(jq -r '.[0].state' "$STATE")"

# green runs: two do not close, a failure in between restarts the count, three in a row close
"$here/auto-found.sh" --green >/dev/null
"$here/auto-found.sh" --green >/dev/null
expect_eq "two green runs leave the issues open" 2 "$(jq '[.[] | select(.state == "OPEN")] | length' "$STATE")"
"$here/auto-found.sh" "$work/two.json" >/dev/null
"$here/auto-found.sh" --green >/dev/null
"$here/auto-found.sh" --green >/dev/null
expect_eq "a failure restarts the green count" 2 "$(jq '[.[] | select(.state == "OPEN")] | length' "$STATE")"
"$here/auto-found.sh" --green >/dev/null
expect_eq "three green runs in a row close the issues" 0 "$(jq '[.[] | select(.state == "OPEN")] | length' "$STATE")"

# another workflow's issues are not touched by a green run
echo '[]' >"$STATE"
"$here/auto-found.sh" "$work/two.json" >/dev/null
for _ in 1 2 3; do WORKFLOW="Other workflow" "$here/auto-found.sh" --green >/dev/null; done
expect_eq "a green run of another workflow closes nothing" 2 "$(jq '[.[] | select(.state == "OPEN")] | length' "$STATE")"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "auto-found tests passed"
