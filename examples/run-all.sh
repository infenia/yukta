#!/usr/bin/env bash
# Runs the examples and prints a summary table.
#   PASS  the example's run.sh exited 0
#   SKIP  exit 3: a plugin or LLM endpoint the example needs is not available
#   FAIL  anything else (the server is down, a status or log assertion failed, ...)
#
# Usage: ./examples/run-all.sh [1|2|3|all]   (default: all)
#   1  standalone      no external dependency
#   2  free-deps       free tools (git, python3, go) or loopback mock servers
#   3  paid-tools      AI agent CLIs and hosted LLMs (run against the fake harness)
# Environment:
#   YUKTA_URL, YUKTA_API_KEY   as usual
#   EXAMPLES_SKIP="01-hello-world ..."   names reported as SKIP without running them
#   EXAMPLES_STRICT=true       a SKIP counts as a failure (the nightly sets this)
set -uo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
source "$root/lib/common.sh"

category="${1:-all}"
case "$category" in
  1) categories=(1-standalone) ;;
  2) categories=(2-free-deps) ;;
  3) categories=(3-paid-tools) ;;
  all) categories=(1-standalone 2-free-deps 3-paid-tools) ;;
  *) echo "usage: $0 [1|2|3|all]" >&2; exit 2 ;;
esac

require_server

declare -a rows
failed=0
for cat in "${categories[@]}"; do
  for dir in "$root/$cat"/[0-9][0-9]-*/; do
    name=$(basename "$dir")
    echo "=================== $name ($cat)"
    if [[ " ${EXAMPLES_SKIP:-} " == *" $name "* ]]; then
      echo "SKIP: listed in EXAMPLES_SKIP"
      rows+=("SKIP  $name")
      [[ "${EXAMPLES_STRICT:-false}" == "true" ]] && failed=1
      continue
    fi
    if [[ ! -f "$dir/run.sh" ]]; then
      echo "SKIP: no run.sh (a walkthrough in its README)"
      rows+=("SKIP  $name")
      [[ "${EXAMPLES_STRICT:-false}" == "true" ]] && failed=1
      continue
    fi
    bash "$dir/run.sh"
    code=$?
    case $code in
      0) rows+=("PASS  $name") ;;
      3)
        rows+=("SKIP  $name")
        [[ "${EXAMPLES_STRICT:-false}" == "true" ]] && failed=1
        ;;
      *) rows+=("FAIL  $name (exit $code)"); failed=1 ;;
    esac
  done
done

echo
echo "=================== summary"
printf '%s\n' "${rows[@]}"
exit $failed
