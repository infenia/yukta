#!/usr/bin/env bash
# Runs every example and prints a summary table.
#   PASS  the example's run.sh exited 0
#   SKIP  exit 3: a plugin or LLM endpoint the example needs is not available
#   FAIL  anything else (the server is down, a status or log assertion failed, ...)
# Usage: ./examples/run-all.sh            (YUKTA_URL / YUKTA_API_KEY apply as usual)
set -uo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
source "$root/lib/common.sh"
require_server

declare -a rows
failed=0
for dir in "$root"/[0-9][0-9]-*/; do
  name=$(basename "$dir")
  echo "=================== $name"
  bash "$dir/run.sh"
  code=$?
  case $code in
    0) rows+=("PASS  $name") ;;
    3) rows+=("SKIP  $name") ;;
    *) rows+=("FAIL  $name (exit $code)"); failed=1 ;;
  esac
done

echo
echo "=================== summary"
printf '%s\n' "${rows[@]}"
exit $failed
