#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Compare a perf report with a baseline and fail on a regression the k6 thresholds do not catch.
#   perf/compare.sh <report dir> <baseline p95 json> [file to write this run's p95 to]
# Fails when a workflow's p95 workflow_e2e is more than P95_TOLERANCE % above the baseline (50) and by more than P95_MIN_DELTA_MS (40; both provisional,
# see perf/BASELINE.md for the run-to-run noise), or when the server RSS keeps rising after the first
# RSS_SKIP_MIN minutes by more than RSS_MIB_PER_HOUR (50; provisional) in a run of at least 30 minutes.
# Stuck runs, lost events and a success rate under 99% already fail the k6 run itself.
set -euo pipefail
dir=${1:?usage: compare.sh <report dir> <baseline p95 json> [out json]}; base=${2:?baseline json}; out=${3:-}
tol=${P95_TOLERANCE:-50}; min_ms=${P95_MIN_DELTA_MS:-40}; slope_max=${RSS_MIB_PER_HOUR:-50}; skip=${RSS_SKIP_MIN:-10}

cur=$(jq -c '[.metrics | to_entries[] | select(.key | test("^workflow_e2e\\{wf:"))
  | {(.key | capture("wf:(?<n>[^}]+)").n): .value["p(95)"]}] | add' "$dir/summary.json")
[[ -z $out ]] || echo "$cur" > "$out"

fail=0
while read -r wf b; do
  c=$(jq -r --arg w "$wf" '.[$w] // empty' <<<"$cur")
  [[ -n $c ]] || continue
  if awk -v c="$c" -v b="$b" -v t="$tol" -v d="$min_ms" 'BEGIN { exit !(c > b * (1 + t / 100) && c - b > d) }'; then
    echo "REGRESSION $wf: p95 ${c} ms, baseline ${b} ms (+${tol}% and +${min_ms} ms allowed)"; fail=1
  else
    echo "ok $wf: p95 ${c} ms (baseline ${b} ms)"
  fi
done < <(jq -r 'to_entries[] | "\(.key) \(.value)"' "$base")

# RSS trend: least-squares slope of the server memory (one sample every 5 s) after the warm-up.
slope=$(awk -F, -v skip="$skip" 'NR > 1 && match($3, /[0-9.]+(MiB|GiB)/) {
  v = substr($3, RSTART, RLENGTH); m = v + 0; if (v ~ /GiB/) m *= 1024
  x = (NR - 2) * 5 / 60; if (x < skip) next
  n++; sx += x; sy += m; sxy += x * m; sxx += x * x; last = x }
  END { if (n > 1 && last >= 30 && n * sxx != sx * sx) printf "%.1f", 60 * (n * sxy - sx * sy) / (n * sxx - sx * sx) }' \
  "$dir/container-stats.csv")
if [[ -z $slope ]]; then
  echo "skip RSS trend: needs a run of at least 30 minutes"
elif awk -v s="$slope" -v m="$slope_max" 'BEGIN { exit !(s > m) }'; then
  echo "REGRESSION RSS rises ${slope} MiB/hour after ${skip} min (limit ${slope_max})"; fail=1
else
  echo "ok RSS trend ${slope} MiB/hour (limit ${slope_max})"
fi
exit $fail
