#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Table test for ci-tasks.sh, the planner that decides which Gradle tasks each CI shard runs.
# A wrong answer here means a PR silently skips (or needlessly repeats) part of the build.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
planner="$here/ci-tasks.sh"
failures=0

# expect <shard> <FULL> <changed-modules-json> <expected tasks (space separated, sorted)>
expect() {
  local shard=$1 full=$2 changed=$3 want=$4 got
  got=$(FULL=$full CHANGED_MODULES="$changed" "$planner" "$shard" | xargs)
  if [ "$got" != "$want" ]; then
    echo "FAIL shard=$shard full=$full changed=$changed"
    echo "  want: '$want'"
    echo "  got:  '$got'"
    failures=$((failures + 1))
  fi
}

# A UI-only change rebuilds the UI and boot (which embeds it), nothing else.
expect ui       false '["ui"]'  ':ui:build'
expect platform false '["ui"]'  ':boot:build'
expect cli      false '["ui"]'  ''
expect infra    false '["ui"]'  ''
expect plugins  false '["ui"]'  ''

# A CLI-only change runs only the CLI shard.
expect cli      false '["cli"]' ':cli:build'
expect platform false '["cli"]' ''
expect ui       false '["cli"]' ''

# A plugin change rebuilds the plugin and boot.
expect plugins  false '["http-request"]' ':plugins:processors:http-request:build'
expect platform false '["http-request"]' ':boot:build'
expect infra    false '["http-request"]' ''

# An infra module change rebuilds the module and boot.
expect infra    false '["workers"]' ':plus:workers:build'
expect platform false '["workers"]' ':boot:build'

# A library module change reruns every shard (everything consumes it), including the leaf shards.
expect platform false '["core"]' ':boot:build :core:build :mcp:build :messaging:build :plugin-api:build :web:build'
expect infra    false '["core"]' ':plus:persistence-postgres:build :plus:build :plus:workers:build'
expect cli      false '["core"]' ':cli:build'
expect ui       false '["core"]' ':ui:build'

# Nothing changed: nothing to run.
for shard in platform infra plugins cli ui; do
  expect "$shard" false '[]' ''
done

# Full build (main, schedule, root-file change) covers every module of the shard.
expect cli      true '[]' ':cli:build'
expect ui       true '[]' ':ui:build'
expect platform true '[]' ':boot:build :core:build :mcp:build :messaging:build :plugin-api:build :web:build'

# A module missing from the planner must fail loudly, not be skipped.
if FULL=false CHANGED_MODULES='["no-such-module"]' "$planner" platform >/dev/null 2>&1; then
  echo "FAIL: an unrecognized module did not make the planner fail"
  failures=$((failures + 1))
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures planner check(s) failed"
  exit 1
fi
echo "ci-tasks.sh: all planner checks passed"
