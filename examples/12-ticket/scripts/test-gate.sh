#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Offline test of ticketkit gate-tests with a fake test runner (design 13.1 items 6, 14, 15, 16, 17).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tk="$here/ticketkit"
fail() { echo "FAIL $*" >&2; exit 1; }
d="$(mktemp -d)"; trap 'rm -rf "$d"' EXIT
tpl="$d/tpl"; rtpl="$d/rtpl"; mkdir -p "$tpl/core/src/main/java" "$tpl/core/src/test/java" "$rtpl/spec"
cd "$tpl" && git init -q -b main . && git config user.email t@t && git config user.name t
echo 'class B {}' >core/src/main/java/B.java && git add -A && git commit -q -m init
cat >"$rtpl/spec/scaffold.patch" <<'P'
diff --git a/core/src/main/java/A.java b/core/src/main/java/A.java
new file mode 100644
--- /dev/null
+++ b/core/src/main/java/A.java
@@ -0,0 +1 @@
+class A { boolean allow() { throw new UnsupportedOperationException("not implemented"); } }
P
jq -n '{tests_mode:"cases_to_code",locked_files:["core/src/test/java/ATest.java"],scaffold_patch:"spec/scaffold.patch",
  test_cases:[{id:"T1"},{id:"T2"}]}' >"$rtpl/spec/spec.json"
echo '{}' >"$rtpl/routing.json"
cat >core_test.java <<'J'
class ATest {
  @Test @DisplayName("[T1] rejects the 101st request") void a() { assertThat(new A().allow()).isFalse(); }
  @Test @DisplayName("[T2] allows the first request") void b() { assertThat(new A().allow()).isTrue(); }
}
J
xml() { # name status(fail|pass|setup) -> a testcase element
  case "$2" in
    fail) echo "<testcase name=\"$1\"><failure type=\"org.opentest4j.AssertionFailedError\" message=\"expected: false but was: true\"/></testcase>" ;;
    nyi) echo "<testcase name=\"$1\"><error type=\"java.lang.UnsupportedOperationException\" message=\"not implemented\"/></testcase>" ;;
    pass) echo "<testcase name=\"$1\"/>" ;;
    setup) echo "<testcase name=\"$1\"><error type=\"java.lang.NoClassDefFoundError\" message=\"com/x/Missing\"/></testcase>" ;;
  esac; }
report() { { echo '<testsuite>'; xml "[T1] rejects the 101st request" "$1"; xml "[T2] allows the first request" "$2"; echo '</testsuite>'; } >"$d/report.xml"; }
cat >"$d/fake.sh" <<SH
#!/usr/bin/env bash
[[ -f "$d/report.xml" ]] || exit 1
mkdir -p build/out && cp "$d/report.xml" build/out/TEST-x.xml; exit 1
SH
chmod +x "$d/fake.sh"
fresh() { rm -rf "$d/w" "$d/run" "$d/report.xml"; cp -r "$tpl" "$d/w"; cp -r "$rtpl" "$d/run"; cp "$tpl/core_test.java" "$d/w/core/src/test/java/ATest.java"; rm -f "$d/w/core_test.java"; }
gate() { (cd "$d/w" && "$tk" gate-tests "$d/run" --worktree . --runner "bash $d/fake.sh" --report junit:build/out 2>&1) || true; }
expect() { # name text-in-output
  grep -q -- "$2" <<<"$out" || fail "$1: expected '$2' in: $out"; }

fresh; report fail nyi; out="$(gate)"
grep -q "errors=0" <<<"$out" || fail "good: $out"
[[ "$(jq -r '.T1[0]' "$d/run/spec/test_map.json")" == "[T1] rejects the 101st request" ]] || fail "test_map built from the report"
jq -e '."core/src/test/java/ATest.java"|length==64' "$d/run/spec/locked.json" >/dev/null || fail "locked hashes recorded"
[[ -f "$d/w/core/src/main/java/A.java" ]] || fail "stubs applied"
out="$(gate)"; grep -q "errors=0" <<<"$out" || fail "second run (stubs already applied) must pass: $out"

fresh; sed -i 's/T2/T9/' "$d/w/core/src/test/java/ATest.java"; report fail fail; out="$(gate)"
expect "14 case without test" "case T2 has no tagged test"; expect "unknown tag" "tag \[T9\]"
fresh; report pass fail; out="$(gate)"; expect "15 passes before implementation" "T1.*not red"
fresh; report setup fail; out="$(gate)"; expect "16 setup error is not red" "setup, import or syntax error"
fresh; out="$(gate)"; expect "6 no report = compile failure" "compile_or_setup"
fresh; report fail fail; echo '// edited' >>"$d/w/core/src/main/java/B.java"; out="$(gate)"; expect "17 source edited" "scope_violation: core/src/main/java/B.java"
fresh; report fail fail; echo x >"$d/w/core/src/main/java/Stray.java"; out="$(gate)"; expect "17 stray file" "scope_violation: core/src/main/java/Stray.java"
fresh; report fail fail; sed -i 's/isFalse()/isFalse(); assertTrue(true)/' "$d/w/core/src/test/java/ATest.java"; out="$(gate)"; expect "trivial assertion" "trivial test (always-true"
fresh; report fail fail; sed -i 's/@Test @Disp/@Disabled @Test @Disp/' "$d/w/core/src/test/java/ATest.java"; out="$(gate)"; expect "skipped test" "trivial test (skipped"
fresh; report fail fail; rm "$d/w/core/src/test/java/ATest.java"; out="$(gate)"; expect "missing locked file" "does not exist"
fresh; jq '.tests_mode="implementer_writes"' "$d/run/spec/spec.json" >"$d/s" && cp "$d/s" "$d/run/spec/spec.json"; out="$(gate)"; expect "implementer_writes skips" "SKIP gate-tests"
[[ ! -f "$d/run/spec/locked.json" ]] || fail "a skipped gate must not lock anything"
# the report parsers for the other ecosystems
fresh; printf '%s\n' '{"testResults":[{"assertionResults":[{"fullName":"[T1] a","status":"failed","failureMessages":["AssertionError: expected 1 to be 2"]},{"fullName":"[T2] b","status":"failed","failureMessages":["SyntaxError: Unexpected token"]}]}]}' >"$d/v.json"
(cd "$d/w" && "$tk" gate-tests "$d/run" --worktree . --runner "mkdir -p build && cp $d/v.json build/v.json" --report vitest:build/v.json 2>&1) >"$d/o" || true; out="$(cat "$d/o")"
expect "vitest: assertion is red, syntax error is not" "case T2.*setup, import or syntax"; grep -q "case T1" <<<"$out" && fail "vitest: an assertion failure must be accepted"
fresh; printf '%s\n' '{"Action":"output","Test":"TestA/[T1]_x","Output":"    a_test.go:9: got 1 want 2\n"}' '{"Action":"fail","Test":"TestA/[T1]_x"}' '{"Action":"output","Test":"TestA/[T2]_y","Output":"panic: runtime error\n"}' '{"Action":"fail","Test":"TestA/[T2]_y"}' >"$d/g.json"
(cd "$d/w" && "$tk" gate-tests "$d/run" --worktree . --runner "cat $d/g.json" --report gotest:stdout 2>&1) >"$d/o" || true; out="$(cat "$d/o")"
expect "go: panic is a setup error" "case T2.*setup, import or syntax"; grep -q "case T1" <<<"$out" && fail "go: got/want failure must be accepted"
echo "PASS ticketkit gate-tests"
