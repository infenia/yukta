#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Offline test of pack-step, scope-check, regen, free-fix, digest and event (design 13.1 items 1-4, 7; 15.13 items 25, 26).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tk="$here/ticketkit"
fail() { echo "FAIL $*" >&2; exit 1; }
d="$(mktemp -d)"; trap 'rm -rf "$d"' EXIT
tpl="$d/tpl"; rtpl="$d/rtpl"; mkdir -p "$tpl/core/src/main/java" "$tpl/core/src/test/java" "$tpl/ui/src/types" "$rtpl/spec" "$rtpl/steps"
cd "$tpl" && git init -q -b main . && git config user.email t@t && git config user.name t
seq 1 30 | sed 's/^/\/\/ line /' >core/src/main/java/A.java
printf 'class LockedTest { void a() { assertThat(1).isEqualTo(1); } }\n' >core/src/test/java/LockedTest.java
printf 'class ExtraTest {\n void a() { assertThat(1).isEqualTo(1); }\n void b() { assertThat(2).isEqualTo(2); }\n}\n' >core/src/test/java/ExtraTest.java
echo '{"v":1}' >ui/openapi.json; echo v1 >ui/src/types/api.generated.ts; echo 'dependencies {}' >core/build.gradle.kts
mkdir -p .claude && echo rule >.claude/rules.md
git add -A && git commit -q -m base && base="$(git rev-parse HEAD)"
step='{"id":"S1","title":"A","depends_on":[],"covers":["T1"],"complexity":"standard","write_files":["core/src/main/java/A.java","core/src/test/java/ExtraTest.java"],
 "read_files":[{"path":"core/src/main/java/A.java","lines":"1-10"}],"instructions":["do"],"contracts":[],"constraints":[],"non_goals":[],
 "done_when":{"commands":["yk gradle :core:test --tests com.infenia.ATest"]},"limits":{"max_diff_lines":20,"max_files":3}}'
jq -n --argjson s "$step" '{spec_version:1,ticket_id:"7",objective:"Limit",risk:"low",assumptions:[],
 scope:{allowed_globs:["core/**"],forbidden_globs:[],forbidden_imports:["lodash"],allow_new_dependencies:false},
 acceptance_criteria:[],tests_mode:"cases_to_code",locked_files:["core/src/test/java/LockedTest.java"],scaffold_patch:"spec/scaffold.patch",
 test_cases:[{id:"T1",title:"t",given:"g",when:"w",expect:"e",kind:"unit",critical:false}],steps:[$s]}' >"$rtpl/spec/spec.json"
jq --arg h "$(sha256sum "$tpl/core/src/test/java/LockedTest.java" | cut -d' ' -f1)" -n '{"core/src/test/java/LockedTest.java":$h}' >"$rtpl/spec/locked.json"
jq '.generated=[{glob:"ui/openapi.json",trigger:"**/web/**/*Controller*.java",regen:"echo {\"v\":2} > ui/openapi.json"},{glob:"ui/src/types/api.generated.ts",trigger:"ui/openapi.json",regen:"echo v2 > ui/src/types/api.generated.ts"}]
  | .free_fix={java:"echo java >> $FFLOG",ui:"echo ui {files} >> $FFLOG",go:"echo go >> $FFLOG"}' "$here/project.json" >"$rtpl/project.json"
echo '{}' >"$rtpl/ticket.json"; : >"$rtpl/events.jsonl"
fresh() { rm -rf "$d/w" "$d/run"; cp -r "$tpl" "$d/w"; cp -r "$rtpl" "$d/run"; }
scope() { (cd "$d/w" && "$tk" scope-check "$d/run" S1 --base "$base" --worktree . 2>&1) || true; }
expect() { grep -q -- "$2" <<<"$out" || fail "$1: expected '$2' in: $out"; }
w() { echo "$d/w/$1"; }

# --- scope-check
fresh; echo '// ok' >>"$(w core/src/main/java/A.java)"; out="$(scope)"; expect "clean change" "errors=0"
fresh; echo x >"$(w core/src/main/java/Other.java)"; out="$(scope)"; expect "1 outside write_files" "scope_violation: core/src/main/java/Other.java is not in write_files"
fresh; echo '// edit' >>"$(w core/src/test/java/LockedTest.java)"; out="$(scope)"; expect "2 locked test" "locked_file_modified: core/src/test/java/LockedTest.java"
fresh; echo 'dependencies { implementation("x") }' >"$(w core/build.gradle.kts)"; out="$(scope)"; expect "3 dependency file" "scope_violation"
fresh; echo 'import lodash;' >>"$(w core/src/main/java/A.java)"; out="$(scope)"; expect "3 forbidden import" "forbidden import lodash"
fresh; seq 1 40 >>"$(w core/src/main/java/A.java)"; out="$(scope)"; expect "4 too many lines" "exceed limits.max_diff_lines"
fresh; echo '@SuppressWarnings("PMD")' >>"$(w core/src/main/java/A.java)"; out="$(scope)"; expect "25 suppression" "new suppression"
fresh; echo '// NOPMD' >>"$(w core/src/main/java/A.java)"; out="$(scope)"; expect "25 NOPMD" "new suppression"
fresh; echo '// eslint-disable-next-line' >>"$(w core/src/main/java/A.java)"; out="$(scope)"; expect "25 eslint" "new suppression"
fresh; printf 'class ExtraTest {\n void a() { assertThat(1).isEqualTo(1); }\n}\n' >"$(w core/src/test/java/ExtraTest.java)"; out="$(scope)"; expect "removed assertion" "assertions were removed"
fresh; echo '{"v":9}' >"$(w ui/openapi.json)"; out="$(scope)"; expect "26 hand-edited generated file" "ui/openapi.json is generated and was edited by hand"
fresh; echo x >"$(w .claude/new.md)"; out="$(scope)"; expect "guard path not in write_files" "not in write_files"
fresh; echo x >"$(w core/src/main/java/A.java)"; echo x >"$(w core/src/test/java/ExtraTest.java)"; echo y >"$(w core/src/main/java/B.java)"; echo y >"$(w core/src/main/java/C.java)"; out="$(scope)"; expect "too many files" "limits.max_files"

# --- regen: a controller change regenerates openapi.json, which triggers the types; generated lines do not count against the limit
fresh; mkdir -p "$d/w/web/src/main/java/w"; echo 'class XController {}' >"$d/w/web/src/main/java/w/XController.java"
jq '.steps[0].write_files+=["web/src/main/java/w/XController.java"]' "$d/run/spec/spec.json" >"$d/s" && cp "$d/s" "$d/run/spec/spec.json"
out="$(cd "$d/w" && "$tk" regen "$d/run" S1 --base "$base" --worktree . 2>&1)"; expect "regen cascade" "ui/openapi.json"; expect "regen cascade" "api.generated.ts"
[[ "$(cat "$d/w/ui/src/types/api.generated.ts")" == v2 ]] || fail "regen: the types were not regenerated"
out="$(scope)"; grep -q "generated and was edited by hand" <<<"$out" && fail "regenerated files must be accepted: $out"; grep -q "errors=0" <<<"$out" || fail "regen then scope-check: $out"
echo hand >"$d/w/ui/src/types/api.generated.ts"; out="$(scope)"; expect "an edit after regen is rejected" "edited by hand"

# --- free-fix
fresh; echo x >>"$(w core/src/main/java/A.java)"; mkdir -p "$d/w/ui/src"; echo 'x' >"$d/w/ui/src/a.tsx"
out="$(cd "$d/w" && FFLOG="$d/ff.log" "$tk" free-fix "$d/run" --base "$base" --worktree . 2>&1)"
grep -q "^java" "$d/ff.log" && grep -q "^ui src/a.tsx" "$d/ff.log" && ! grep -q "^go" "$d/ff.log" || fail "free-fix per ecosystem: $(cat "$d/ff.log")"

# --- pack-step
fresh; out="$(cd "$d/w" && "$tk" pack-step "$d/run" S1 --worktree . 2>&1)"; expect "pack" "PACKED S1"
h1="$(cat "$d/run/prefix.hash")"; cp "$d/run/steps/S1/packet.json" "$d/p1.json"; cp "$d/run/prefix.txt" "$d/pre1"
(cd "$d/w" && "$tk" pack-step "$d/run" S1 --worktree . >/dev/null)
cmp -s "$d/p1.json" "$d/run/steps/S1/packet.json" && cmp -s "$d/pre1" "$d/run/prefix.txt" && [[ "$h1" == "$(cat "$d/run/prefix.hash")" ]] || fail "pack-step must be byte-stable"
[[ "$(jq -r '.context_files[]|select(.path=="core/src/main/java/A.java" and .lines=="1-10")|.content|split("\n")|length' "$d/run/steps/S1/packet.json")" == 10 ]] || fail "read_files line range"
[[ "$(jq '.cases|length' "$d/run/steps/S1/packet.json")" == 1 ]] || fail "only the covered cases"
grep -q '"allowed_globs"' "$d/run/prefix.txt" || fail "prefix content"
jq -e '.events' /dev/null 2>/dev/null || true
grep -q '"prefix_hash"' "$d/run/events.jsonl" || fail "pack-step must log the prefix hash"
jq '.steps[0].read_files=[{"path":"ui/openapi.json"}]' "$d/run/spec/spec.json" >"$d/s" && cp "$d/s" "$d/run/spec/spec.json"
out="$(cd "$d/w" && "$tk" pack-step "$d/run" S1 --worktree . 2>&1 || true)"; expect "deny list" "on the deny list"
# a changed prefix inside one run is refused
jq '.steps[0].read_files=[]|.scope.forbidden_imports=["moment"]' "$d/run/spec/spec.json" >"$d/s" && cp "$d/s" "$d/run/spec/spec.json"
out="$(cd "$d/w" && "$tk" pack-step "$d/run" S1 --worktree . 2>&1 || true)"; expect "prefix drift" "cache prefix changed"

# --- digest
mkdir -p "$d/j"; cat >"$d/j/TEST-a.xml" <<'X'
<testsuite><testcase classname="com.infenia.ATest" name="[T1] rejects"><failure message="expected: false but was: true">org.opentest4j.AssertionFailedError: x
	at com.infenia.A.allow(A.java:42)
	at org.junit.platform.Foo.run(Foo.java:9)</failure></testcase><testcase classname="com.infenia.ATest" name="ok"/></testsuite>
X
fresh; out="$("$tk" digest "$d/run" S1 --attempt 2 --stage tests --junit "$d/j" --input /dev/null)"
[[ "$(jq -r '.class' <<<"$out")" == test_fail && "$(jq -r '.failing[0]' <<<"$out")" == "com.infenia.ATest.[T1] rejects" && "$(jq -r '.frames[0]' <<<"$out")" == "com.infenia.A.allow(A.java:42)" ]] || fail "digest from JUnit: $out"
[[ "$(jq '.frames|length' <<<"$out")" == 1 ]] || fail "digest keeps repo frames only: $out"
big="$(head -c 6000 /dev/zero | tr '\0' x)"; printf 'OUTPUT style: %s\n' "$big" >"$d/big.txt"
out="$("$tk" digest "$d/run" S1 --attempt 1 --stage style --input "$d/big.txt")"
(( ${#out} <= 2048 )) && [[ "$(jq -r '.truncated' <<<"$out")" == true && "$(jq -r .class <<<"$out")" == lint_fail ]] || fail "digest over 2 KB must be cut and flagged (${#out} bytes)"
[[ -f "$d/run/steps/S1/attempt-1/failure.json" ]] || fail "digest file"
(cd "$d/w" && "$tk" pack-step "$d/run" S1 --worktree . >/dev/null 2>&1) || true
jq -e '.prior_attempts|length==2' "$d/run/steps/S1/packet.json" >/dev/null || fail "pack-step must carry the earlier failure digests"

# --- event
echo '{"usage":{"input_tokens":5200,"output_tokens":900,"cache_read_input_tokens":4100,"cache_creation_input_tokens":10},"total_cost_usd":0.004,"duration_ms":14200}' >"$d/cr.json"
"$tk" event "$d/run" role=implementer step_id=S1 attempt=1 model=haiku --claude-result "$d/cr.json"
tail -1 "$d/run/events.jsonl" | jq -e '.role=="implementer" and .attempt==1 and .cache_read_tokens==4100 and .cache_write_tokens==10 and .cost_usd==0.004 and .tokens_in==5200' >/dev/null || fail "event fields"
echo "PASS ticketkit step tools"
