#!/usr/bin/env bash
# Use case: an application as a supervisor. Checks the reference agents (Python and Go) without a server:
# what each one decides for a failed node, a review and everything else. Exit 3 (SKIP) when a tool is missing.
set -euo pipefail
cd "$(dirname "$0")"
missing=0
for tool in python3 go; do
  command -v "$tool" >/dev/null || { echo "SKIP: $tool is not installed"; missing=1; }
done
[[ $missing == 0 ]] || exit 3
echo "--> Python reference agent"
python3 test_agent.py
echo "--> Go reference agent"
(cd go && "$(command -v go)" test ./...)
echo "reference agents OK"
