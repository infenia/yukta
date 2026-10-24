#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Table test for mutation-ratchet.py on fixture pitest reports.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0

# report DIR KILLED SURVIVED NON_VIABLE: a pitest report with that many mutants of each status
report() {
  mkdir -p "$work/$1/build/reports/pitest"
  {
    echo '<mutations>'
    for _ in $(seq 1 "$2"); do echo '<mutation detected="true" status="KILLED"/>'; done
    for _ in $(seq 1 "$3"); do echo '<mutation detected="false" status="SURVIVED"/>'; done
    for _ in $(seq 1 "$4"); do echo '<mutation detected="false" status="NON_VIABLE"/>'; done
    echo '</mutations>'
  } >"$work/$1/build/reports/pitest/mutations.xml"
}

# expect NAME WANT_EXIT [extra args]
expect() {
  local name=$1 want=$2
  shift 2
  local got=0
  python3 -I "$here/mutation-ratchet.py" --root "$work" --baseline "$work/baseline.json" "$@" >"$work/out" 2>&1 || got=$?
  if [ "$got" != "$want" ]; then
    echo "FAIL $name: want exit $want, got $got"
    cat "$work/out"
    failures=$((failures + 1))
  fi
}

report core 80 20 5          # 80 of 100 (the 5 that do not compile are not counted)
echo '{"core": 0.8}' >"$work/baseline.json"
expect "same score passes" 0
echo '{"core": 0.85}' >"$work/baseline.json"
expect "a drop of 5 points fails" 1
echo '{"core": 0.805}' >"$work/baseline.json"
expect "a drop within the tolerance passes" 0
echo '{}' >"$work/baseline.json"
expect "a new module is not a failure" 0
report web 10 0 0
echo '{"core": 0.7, "web": 0.9}' >"$work/baseline.json"
expect "update never lowers a baseline" 0 --update
python3 -I -c "
import json,sys
b=json.load(open('$work/baseline.json'))
sys.exit(0 if b['web']==1.0 and b['core']==0.8 else 1)" || { echo "FAIL update: $(cat "$work/baseline.json")"; failures=$((failures + 1)); }
rm -rf "$work/core" "$work/web"
expect "no report is an error" 2

if [ "$failures" -gt 0 ]; then
  echo "$failures mutation-ratchet check(s) failed"
  exit 1
fi
echo "mutation-ratchet.py: all checks passed"
