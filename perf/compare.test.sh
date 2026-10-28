#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Table test for compare.py on fixture k6 summaries: what it must flag and what it must let pass.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0
echo '{"mapper": {"p50": 57, "p99": 120}}' >"$work/baseline.json"

# summary MEDIAN P99 [SUCCESS] [STUCK] [LOST] [TEARDOWN_ERRORS]: a k6 summary for the workflow "mapper"
summary() {
  jq -n --argjson med "$1" --argjson p99 "$2" --argjson ok "${3:-1}" --argjson stuck "${4:-0}" --argjson lost "${5:-0}" --argjson blind "${6:-0}" '{metrics: {
    "workflow_e2e{wf:mapper}": {med: $med, "p(95)": 999, "p(99)": $p99},
    workflow_success: {rate: $ok}, "workflow_success{wf:mapper}": {rate: $ok},
    stuck_runs: {count: $stuck}, teardown_errors: {count: $blind}, tracker_events_lost: {count: $lost}}}'
}

# run NAME WANT_EXIT WANT_TEXT: compare.py on $work/report, printing what it flagged
run() {
  local got=0 out
  out=$(python3 -I "$here/compare.py" "$work/report" --baseline "$work/baseline.json" --findings "$work/findings.json" 2>&1) || got=$?
  if [ "$got" != "$2" ] || { [ -n "$3" ] && ! grep -q -- "$3" <<<"$out"; }; then
    echo "FAIL $1: want exit $2 and '$3', got exit $got:"
    echo "$out"
    failures=$((failures + 1))
  fi
}

mkdir -p "$work/report"
summary 57 118 >"$work/report/summary.json"
run "a run like the baseline passes" 0 "no regression"
summary 60 150 >"$work/report/summary.json"
run "inside the limits (p99 150 <= 120*1.3+25) passes" 0 "no regression"
summary 90 118 >"$work/report/summary.json"
run "a median of 90 ms passes (limit 96)" 0 "no regression"
summary 100 118 >"$work/report/summary.json"
run "a median of 100 ms fails" 1 "mapper median latency regressed"
summary 57 200 >"$work/report/summary.json"
run "a p99 of 200 ms fails" 1 "mapper p99 latency regressed"
summary 57 118 0.9 >"$work/report/summary.json"
run "a success rate of 90% fails" 1 "success rate below 99%"
summary 57 118 1 3 >"$work/report/summary.json"
run "stuck runs fail" 1 "runs that never finished"
expect_critical=$(jq -r '.[0].severity' "$work/findings.json")
[ "$expect_critical" = Critical ] || { echo "FAIL stuck runs are Critical, got $expect_critical"; failures=$((failures + 1)); }
summary 57 118 1 0 2 >"$work/report/summary.json"
run "lost tracker events fail" 1 "lost tracker events"
summary 57 118 1 0 0 1 >"$work/report/summary.json"
run "a stuck-run check that could not look fails" 1 "stuck-run checks that could not look"

# The p95 is not checked: a wild p95 with a good median and p99 passes.
summary 57 118 >"$work/report/summary.json"
jq '.metrics["workflow_e2e{wf:mapper}"]["p(95)"] = 5000' "$work/report/summary.json" >"$work/s.json" && mv "$work/s.json" "$work/report/summary.json"
run "the p95 alone never fails a run" 0 "no regression"

# --write-baseline: a run becomes the next baseline, and passes against it
summary 61 119 >"$work/report/summary.json"
python3 -I "$here/compare.py" "$work/report" --baseline "$work/baseline.json" --write-baseline "$work/next.json" >/dev/null
[ "$(jq -c . "$work/next.json")" = '{"mapper":{"p50":61,"p99":119}}' ] || { echo "FAIL write-baseline: $(cat "$work/next.json")"; failures=$((failures + 1)); }
python3 -I "$here/compare.py" "$work/report" --baseline "$work/next.json" >/dev/null || { echo "FAIL a run fails against its own baseline"; failures=$((failures + 1)); }

# memory: a 30 minute run, one sample a minute
steady() { awk 'BEGIN{print "time,cpu_perc,mem_usage"; for(i=0;i<31;i++) printf "14:%02d:00,5%%,%.1fMiB / 8GiB\n", i, 400+(i<6?i*10:60)}'; }
leak() { awk 'BEGIN{print "time,cpu_perc,mem_usage"; for(i=0;i<31;i++) printf "14:%02d:00,5%%,%.1fMiB / 8GiB\n", i, 400+i*20}'; }
summary 57 118 >"$work/report/summary.json"
steady >"$work/report/container-stats.csv"
run "memory that levels off passes" 0 "no regression"
leak >"$work/report/container-stats.csv"
run "memory that keeps growing fails" 1 "memory keeps growing"
printf 'time,cpu_perc,mem_usage\n14:00:00,5%%,400MiB / 8GiB\n14:02:00,5%%,900MiB / 8GiB\n' >"$work/report/container-stats.csv"
run "a run shorter than 15 minutes is not judged on memory" 0 "no regression"

if [ "$failures" -gt 0 ]; then
  echo "$failures compare.py check(s) failed"
  exit 1
fi
echo "compare.py: all checks passed"
