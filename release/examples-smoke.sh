#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Run the examples (examples/run-all.sh) against a running server, so a release binary or image whose
# examples fail is never published. smoke-test.sh checks the Quickstart only; the examples exercise
# the rest of the basics: supervisors, approvals, sub-workflows, file and key-value steps, the
# schedule trigger and MCP. A native image fails here where a JVM test does not (reflection data).
#
# Fails when an example fails, or when one is skipped that is not named in SMOKE_ALLOW_SKIP.
#
# Usage: release/examples-smoke.sh <base-url> [api-key]
#   SMOKE_ALLOW_SKIP  space separated example names that may be skipped. The default names the
#                     examples that need an LLM endpoint or are a walkthrough without run.sh.
#                     A server that does not allow `sh` (the Docker image has none) must name
#                     the examples that run commands.
#   EXAMPLES_SKIP     examples not run at all (see examples/run-all.sh)
#
# The server under test needs YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo,python3 for examples 01, 02, 03 and 08.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -ge 1 ]] || die "usage: examples-smoke.sh <base-url> [api-key]"
export YUKTA_URL="${1%/}"
[[ -z "${2:-}" ]] || export YUKTA_API_KEY="$2"
need curl
need jq

allow_skip=" ${SMOKE_ALLOW_SKIP:-09-llm-chat-agent 15-input-pull 16-supervisor-profiles 17-supervisor-workflow} "

# run-all.sh ends with one line per example: "PASS  name", "SKIP  name" or "FAIL  name (exit N)".
# EXAMPLES_SKIP=dogfood: that example runs this repository's own Gradle check.
log "running the examples against $YUKTA_URL"
set +e
output="$(EXAMPLES_SKIP="${EXAMPLES_SKIP:-dogfood}" bash "$REPO_ROOT/examples/run-all.sh" 2>&1)"
code=$?
set -e
summary="$(sed -n '/^=* summary$/,$p' <<< "$output" | tail -n +2)"

if [[ -z "$summary" ]]; then
  echo "$output" | tail -30 >&2
  die "examples smoke: run-all.sh printed no summary (exit $code)"
fi
echo "$summary"

failed=0
while read -r state name _; do
  case "$state" in
    FAIL) echo "examples smoke: $name failed" >&2; failed=1 ;;
    SKIP)
      if [[ "$allow_skip" != *" $name "* && " ${EXAMPLES_SKIP:-dogfood} " != *" $name "* ]]; then
        echo "examples smoke: $name was skipped, which this server must not do" >&2
        failed=1
      fi ;;
  esac
done <<< "$summary"

passed="$(grep -c '^PASS' <<< "$summary" || true)"
(( passed > 0 )) || { echo "examples smoke: no example passed" >&2; failed=1; }
if (( failed )); then
  echo "$output" | tail -60 >&2
  die "examples smoke failed"
fi
log "examples smoke passed: $passed examples"
