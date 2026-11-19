#!/bin/sh
# Runs patch-guard.sh against a throwaway repo for each rule. Prints PASS or the first failure.
set -eu
G="$(cd "$(dirname "$0")" && pwd)/patch-guard.sh"
R=$(mktemp -d); trap 'rm -rf "$R"' EXIT
cd "$R"; git init -q; mkdir -p src/test; printf '@Test a\n@Test b\n' >src/test/AppTest.java
echo v1 >App.java; echo x >build.sh
git add -A; git -c user.name=t -c user.email=t@t commit -qm base
args="--dir $R --protected build.sh --forbid @Disabled --no-test-removal --require-change"
expect() {  # NAME WANT_EXIT WANT_SUBSTRING
  out=$(sh "$G" $args) && rc=0 || rc=$?
  [ "$rc" = "$2" ] && { [ -z "$3" ] || printf %s "$out" | grep -qF "$3"; } || { echo "FAIL $1 (rc=$rc): $out"; exit 1; }
}
expect empty 1 '"rule":"requireChange"'
echo v2 >App.java;                      expect clean 0 '"violations":[]'
echo y >>build.sh;                      expect protected 1 'protected path changed'; git checkout -q build.sh
echo '@Disabled' >new.txt;              expect forbidden 1 'matches @Disabled'; rm new.txt
rm src/test/AppTest.java;               expect test-deleted 1 'test file deleted'; git checkout -q src
echo '@Test a' >src/test/AppTest.java;  expect test-removed 1 '1 test(s) removed'
echo PASS
