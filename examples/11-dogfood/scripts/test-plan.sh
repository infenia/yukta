#!/usr/bin/env bash
# Offline test of plan.sh's class-level test selection (Tier 1) in a throwaway git repo: core <- web.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
repo="$(mktemp -d)"
trap 'rm -rf "$repo"' EXIT
cd "$repo"
git init -q
mkdir -p d/scripts core/src/main/java/p core/src/test/java/p web/src/main/java/w
cp "$here/scripts/plan.sh" d/scripts/; cp "$here/heal.json" d/
printf 'include("core")\ninclude("web")\n' >settings.gradle.kts
echo 'plugins { id("x") }' >core/build.gradle.kts
echo 'dependencies { implementation(project(":core")) }' >web/build.gradle.kts
printf 'package p;\nclass Foo {\n  int f() { return 1; }\n}\n' >core/src/main/java/p/Foo.java
printf 'package p;\nclass FooTest {}\n' >core/src/test/java/p/FooTest.java
printf 'package p;\nclass Bar {}\n' >core/src/main/java/p/Bar.java
echo 'package w; class W {}' >web/src/main/java/w/W.java
git add -A && git -c user.email=t@t -c user.name=t commit -qm init
plan() { d/scripts/plan.sh --base HEAD; }
tests_line() { plan | awk -F'\t' '$1 == "tests" { print $3 }'; }

# a private change in a class with a test: only that test, no dependants, no coverage stage
sed -i 's/return 1/return 2/' core/src/main/java/p/Foo.java
[[ "$(tests_line)" == "./gradlew :core:test --tests p.FooTest -q --console=plain" ]] || fail "tier 1: $(tests_line)"
plan | grep -q '^coverage' && fail "tier 1 still plans coverage"
# a changed test class alone: that test
git checkout -q . && echo '// c' >>core/src/test/java/p/FooTest.java
[[ "$(tests_line)" == *":core:test --tests p.FooTest "* ]] || fail "test only: $(tests_line)"
# a public member touched: the whole module and its dependants
git checkout -q . && sed -i 's/  int f/  public int f/' core/src/main/java/p/Foo.java
[[ "$(tests_line)" == *":web:test"* ]] || fail "public change should keep dependants: $(tests_line)"
# a class without a test: the whole module
git checkout -q . && echo '// c' >>core/src/main/java/p/Bar.java
[[ "$(tests_line)" == *":core:jacocoTestReport"* ]] || fail "no test class should not narrow: $(tests_line)"
echo "ok   plan"
