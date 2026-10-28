#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# `yk check` (examples/11-dogfood/scripts/plan.sh) and CI (ci-tasks.sh) each decide which Gradle modules a
# change affects. This test makes them agree: for the same changed file, in a copy of the real module graph,
# both must name the same JVM modules (cli and ui are other ecosystems, which plan.sh plans separately), and
# both must rebuild every module for a build-logic change (ci-tasks.sh does the same for a test-support change,
# which every module depends on, so the two lists are equal there too). It fails the moment one of them gets a new rule the other lacks.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
src=$(cd "$here/../.." && pwd)
ci="$src/.github/scripts/ci-tasks.sh"
plan_script="$src/examples/11-dogfood/scripts/plan.sh"

repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT
cd "$src"
git ls-files settings.gradle.kts '*build.gradle.kts' | while read -r f; do
  mkdir -p "$repo/$(dirname "$f")"
  cp "$f" "$repo/$f"
done
mkdir -p "$repo/examples/11-dogfood/scripts"
cp "$plan_script" "$repo/examples/11-dogfood/scripts/"
cp "$src/examples/11-dogfood/heal.json" "$repo/examples/11-dogfood/"
cd "$repo"
git init -q
git add -A
git -c user.email=t@t -c user.name=t commit -qm graph

failures=0
fail() {
  echo "FAIL $1"
  echo "  plan.sh:  $2"
  echo "  ci-tasks: $3"
  failures=$((failures + 1))
}

# Every JVM module: what an unscoped (full) run builds.
all_modules() {
  sed -n 's/^include("\(.*\)")/:\1/p' settings.gradle.kts | grep -v -e '^:cli$' -e '^:ui$' | sort -u
}

# The JVM modules plan.sh would build for the current working tree, one per line, sorted.
plan_modules() {
  local out
  out=$(examples/11-dogfood/scripts/plan.sh --base HEAD)
  if grep -q '^# gradle: every stage' <<<"$out"; then
    all_modules
  else
    sed -n 's/^# gradle: changed \(.*\); dependents (compile and tests only): \(.*\)$/\1 \2/p' <<<"$out" |
      tr ' ' '\n' | grep -v -e '^none$' -e '^$' | sed 's/^/:/' | sort -u
  fi
}

# The JVM modules ci-tasks.sh affects for the changed files on stdin.
ci_modules() {
  local plan
  plan=$(REPO_ROOT=$repo "$ci")
  jq -r '.affected[] | select(. != ":cli" and . != ":ui")' <<<"$plan" | sort -u
}

mapfile -t modules < <(sed -n 's/^include("\(.*\)")/\1/p' settings.gradle.kts)
checked=0
for m in "${modules[@]}"; do
  dir=${m//://}
  [ -f "$dir/build.gradle.kts" ] || continue
  case $m in cli | ui) continue ;; esac
  echo "// touched" >>"$dir/build.gradle.kts"
  want=$(echo "$dir/build.gradle.kts" | ci_modules)
  got=$(plan_modules)
  git checkout -q .
  [ "$got" = "$want" ] || fail "a change under :$m" "$(tr '\n' ' ' <<<"$got")" "$(tr '\n' ' ' <<<"$want")"
  checked=$((checked + 1))
done

# A build-logic change rebuilds everything in both.
mkdir -p build-logic
echo "// touched" >build-logic/x.gradle.kts
want=$(echo build-logic/x.gradle.kts | ci_modules)
got=$(plan_modules)
[ "$got" = "$(all_modules)" ] && [ "$want" = "$got" ] || fail "a build-logic change" "$(tr '\n' ' ' <<<"$got")" "$(tr '\n' ' ' <<<"$want")"
rm -rf build-logic

[ "$checked" -gt 0 ] || { echo "FAIL no modules found in settings.gradle.kts"; exit 1; }
if [ "$failures" -gt 0 ]; then
  echo "$failures plan(s) differ between plan.sh and ci-tasks.sh"
  exit 1
fi
echo "ok   plan.sh and ci-tasks.sh agree on $checked modules and on a build-logic change"
