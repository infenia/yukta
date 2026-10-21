#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Table test for ci-tasks.sh, the planner that decides what a CI run builds.
# A wrong answer here means a PR silently skips (or needlessly repeats) part of the build.
# It runs against the real module graph, so a new project() dependency that changes a plan
# shows up here for review.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
planner="$here/ci-tasks.sh"
failures=0

fail() {
  echo "FAIL $1"
  echo "  want: '$2'"
  echo "  got:  '$3'"
  failures=$((failures + 1))
}

# expect <changed files, space separated> <jq filter> <expected output (compact)>
expect() {
  local files=$1 filter=$2 want=$3 got
  got=$(tr ' ' '\n' <<<"$files" | FULL=false "$planner" | jq -c "$filter")
  [ "$got" = "$want" ] || fail "files='$files' filter='$filter'" "$want" "$got"
}

affected='.affected | join(" ")'
shards='[.shards[].name] | join(" ")'

# Library modules rebuild only what consumes them, not the whole repository.
expect 'web/src/A.java' "$affected" '":boot :web"'
expect 'mcp/src/A.java' "$affected" '":boot :mcp"'
expect 'core/src/A.java' "$affected" \
  '":boot :core :mcp :plugins:processors:internal:internal-core :plugins:processors:internal:script-js :plus :plus:persistence-postgres :plus:workers :web"'
expect 'core/src/A.java' "$shards" '"platform infra plugins postgres-acceptance plus-acceptance"'
expect 'messaging/src/A.java' '.affected | length' '28'
expect 'messaging/src/A.java' '.affected | index(":cli")' 'null'

# Plugin-to-plugin dependencies are followed transitively.
expect 'plugins/processors/llm-call/src/A.java' "$affected" \
  '":boot :plugins:control-planes:model-control-plane :plugins:control-planes:rules-first-control-plane :plugins:processors:decision :plugins:processors:llm-call"'
expect 'plugins/supervisors/rule-supervisor/src/A.java' "$affected" \
  '":boot :plugins:control-planes:rule-control-plane :plugins:control-planes:rules-first-control-plane :plugins:supervisors:rule-supervisor"'
expect 'plugins/processors/internal/internal-core/src/A.java' "$affected" \
  '":boot :plugins:processors:internal:internal-core :plugins:processors:internal:script-js"'
expect 'plugins/processors/http-request/src/A.java' "$shards" '"platform plugins"'

# The deepest module owns a path: plus/workers is not plus.
expect 'plus/workers/src/A.java' "$affected" '":boot :plus:workers"'
expect 'plus/workers/src/A.java' "$shards" '"platform infra"'
expect 'plus/src/A.java' "$shards" '"platform infra plus-acceptance"'
expect 'plus/persistence-postgres/src/A.java' "$shards" '"platform infra postgres-acceptance"'

# Leaf modules: the UI rebuilds boot (which embeds it) and runs E2E; the CLI stands alone.
expect 'ui/src/a.ts' "$affected" '":boot :ui"'
expect 'ui/src/a.ts' '.e2e' 'true'
expect 'cli/main.go' "$shards" '"cli"'
expect 'cli/main.go' '.e2e' 'false'

# A boot change runs both acceptance suites.
expect 'boot/src/A.java' "$shards" '"platform postgres-acceptance plus-acceptance"'

# Lint covers the affected JVM modules; boot gets OpenGrep only, cli and ui none.
expect 'web/src/A.java' '.lint' \
  '":boot:opengrep :web:opengrep :web:checkstyleMain :web:checkstyleTest :web:pmdMain :web:pmdTest"'
expect 'cli/main.go' '.lint' '""'

# Files outside any module build nothing.
expect 'docs/a.md README.md examples/x/README.md' "$shards" '""'
expect '' '.full' 'false'

# Root/build files force the full build; so does a module lockfile's sibling at the root only.
expect 'build-logic/src/x.kts' '.full' 'true'
expect 'gradle/libs.versions.toml' '.full' 'true'
expect 'docs/a.md .github/workflows/ci.yml' '.full' 'true'
expect 'core/gradle.lockfile' '.full' 'false'

# FULL=true (main, schedule, dispatch, `ci:full` label) runs every shard and E2E.
got=$(FULL=true "$planner" </dev/null | jq -c '[([.shards[].name] | join(" ")), (.affected | length), .e2e]')
want='["platform infra plugins cli ui postgres-acceptance plus-acceptance",30,true]'
[ "$got" = "$want" ] || fail "FULL=true" "$want" "$got"

# A dependency on a module missing from settings.gradle.kts must fail loudly, not be skipped.
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/a"
echo 'include("a")' >"$fixture/settings.gradle.kts"
echo 'dependencies { implementation(project(":gone")) }' >"$fixture/a/build.gradle.kts"
if REPO_ROOT=$fixture "$planner" --check >/dev/null 2>&1; then
  echo "FAIL: a dangling project() dependency did not make the planner fail"
  failures=$((failures + 1))
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures planner check(s) failed"
  exit 1
fi
echo "ci-tasks.sh: all planner checks passed"
