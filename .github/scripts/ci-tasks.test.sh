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
expect 'web/src/A.java' "$affected" '":architecture :boot :e2e :plus:plus-architecture :web"'
expect 'mcp/src/A.java' "$affected" '":architecture :boot :e2e :mcp :plus:plus-architecture"'
expect 'core/src/A.java' "$affected" \
  '":architecture :boot :core :e2e :mcp :plus :plus:persistence-postgres :plus:plus-architecture :plus:workers :web"'
expect 'core/src/A.java' "$shards" '"platform infra e2e postgres-acceptance workers-acceptance plus-acceptance"'
# 31 modules: :plugins:processors:agent-session depends on :messaging, so it is rebuilt with it
expect 'messaging/src/A.java' '.affected | length' '32'
expect 'messaging/src/A.java' '.affected | index(":cli")' 'null'

# Plugin-to-plugin dependencies are followed transitively.
expect 'plugins/processors/llm-call/src/A.java' "$affected" \
  '":architecture :boot :e2e :plugins:processors:decision :plugins:processors:llm-call :plus:plus-architecture"'
expect 'plugins/supervisors/rules/src/A.java' "$affected" \
  '":architecture :boot :e2e :plugins:supervisors:rules :plus:plus-architecture"'
expect 'plugins/processors/internal/internal-core/src/A.java' "$affected" \
  '":architecture :boot :e2e :plugins:processors:internal:internal-core :plugins:processors:internal:script-js :plus:plus-architecture"'
expect 'plugins/processors/http-request/src/A.java' "$shards" '"platform infra plugins e2e"'

# The deepest module owns a path: plus/workers is not plus.
expect 'plus/workers/src/A.java' "$affected" '":boot :e2e :plus:plus-architecture :plus:workers"'
expect 'plus/workers/src/A.java' "$shards" '"platform infra e2e workers-acceptance"'
expect 'plus/src/A.java' "$shards" '"platform infra e2e postgres-acceptance plus-acceptance"'
expect 'plus/persistence-postgres/src/A.java' "$shards" '"platform infra e2e postgres-acceptance"'

# Leaf modules: the UI rebuilds boot (which embeds it) and runs E2E; the CLI stands alone.
expect 'ui/src/a.ts' "$affected" '":boot :e2e :ui"'
expect 'ui/src/a.ts' '.e2e' 'true'
expect 'cli/main.go' "$shards" '"cli"'
expect 'cli/main.go' '.e2e' 'false'

# A boot change runs all three acceptance suites.
expect 'boot/src/A.java' "$shards" '"platform e2e postgres-acceptance workers-acceptance plus-acceptance"'

# Lint covers the affected JVM modules; boot gets OpenGrep only, cli and ui none.
expect 'web/src/A.java' '.lint' \
  '":architecture:opengrep :architecture:checkstyleMain :architecture:checkstyleTest :architecture:pmdMain :architecture:pmdTest :boot:opengrep :e2e:opengrep :e2e:checkstyleMain :e2e:checkstyleTest :e2e:pmdMain :e2e:pmdTest :plus:plus-architecture:opengrep :plus:plus-architecture:checkstyleMain :plus:plus-architecture:checkstyleTest :plus:plus-architecture:pmdMain :plus:plus-architecture:pmdTest :web:opengrep :web:checkstyleMain :web:checkstyleTest :web:pmdMain :web:pmdTest"'
expect 'cli/main.go' '.lint' '""'

# The shards skip what the lint job already ran (partial runs only), so no check runs twice.
shard_args='.shards[0].args'
expect 'web/src/A.java' "$shard_args" \
  '":architecture:build :boot:build :web:build --continue -x spotlessCheck -x :architecture:opengrep -x :architecture:checkstyleMain -x :architecture:checkstyleTest -x :architecture:pmdMain -x :architecture:pmdTest -x :boot:opengrep -x :e2e:opengrep -x :e2e:checkstyleMain -x :e2e:checkstyleTest -x :e2e:pmdMain -x :e2e:pmdTest -x :plus:plus-architecture:opengrep -x :plus:plus-architecture:checkstyleMain -x :plus:plus-architecture:checkstyleTest -x :plus:plus-architecture:pmdMain -x :plus:plus-architecture:pmdTest -x :web:opengrep -x :web:checkstyleMain -x :web:checkstyleTest -x :web:pmdMain -x :web:pmdTest"'
expect 'cli/main.go' "$shard_args" '":cli:build --continue"'
got=$(FULL=true "$planner" </dev/null | jq -c '[.shards[].args | contains(" -x ")] | any')
[ "$got" = false ] || fail "FULL=true shards must not skip checks" false "$got"

# Files outside any module build nothing.
expect 'docs/a.md README.md examples/x/README.md' "$shards" '""'
expect '' '.full' 'false'

# Root/build files force the full build; so does a module lockfile's sibling at the root only.
expect 'build-logic/src/x.kts' '.full' 'true'
expect 'gradle/libs.versions.toml' '.full' 'true'
expect 'docs/a.md .github/workflows/ci.yml' '.full' 'true'
expect 'test-support/src/A.java' '.full' 'true'
expect 'core/gradle.lockfile' '.full' 'false'

# FULL=true (main, schedule, dispatch, `ci:full` label) runs every shard and E2E.
got=$(FULL=true "$planner" </dev/null | jq -c '[([.shards[].name] | join(" ")), (.affected | length), .e2e]')
want='["platform infra plugins cli ui e2e postgres-acceptance workers-acceptance plus-acceptance",35,true]'
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
