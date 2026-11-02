#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Offline test of ticketkit init, route and validate-spec on a throwaway git repo (design 13.1 items 5, 18, 19, 22; 15.13 items 24, 29).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tk="$here/ticketkit"
fail() { echo "FAIL $*" >&2; exit 1; }
d="$(mktemp -d)"; trap 'rm -rf "$d"' EXIT
repo="$d/repo"; mkdir -p "$repo/core/src/main/java" "$repo/web" "$repo/core" && cd "$repo"
git init -q -b main . && git config user.email t@t && git config user.name t
seq 1 50 | sed 's/^/\/\/ line /' >core/src/main/java/B.java
echo 'plugins { }' >core/build.gradle.kts
echo 'dependencies { implementation(project(":core")) }' >web/build.gradle.kts
git add -A && git commit -q -m init
state="$d/state"
mkticket() { # number title labels-json body -> ticket file
  jq -n --argjson n "$1" --arg t "$2" --argjson l "$3" --arg b "$4" '{number:$n,title:$t,labels:($l|map({name:.})),body:$b}' >"$d/t$1.json"; }
body=$'## Goal\nx\n\n## Acceptance criteria\n- [ ] tests pass\n- [ ] limiter rejects the 101st request\n\n## Notes\n- not a criterion'

# --- init
mkticket 7 "feat(core): rate limit" '["core"]' "$body"
out="$("$tk" init --ticket-file "$d/t7.json" --root "$repo" --state-dir "$state" --base HEAD --no-worktree)"
run="$(sed -n 's/^RUN_DIR=//p' <<<"$out")"
for f in ticket.json project.json events.jsonl spec steps; do [[ -e "$run/$f" ]] || fail "init: $f missing"; done
[[ "$(jq -r '.acceptance_criteria|length' "$run/ticket.json")" == 2 ]] || fail "init: only the two criteria under the heading count"
[[ "$(jq -r .kind "$run/ticket.json")" == feature ]] || fail "init: kind from the title"
mkticket 8 "fix(core): bug" '["core"]' $'no criteria here\n- a list item'
set +e; "$tk" init --ticket-file "$d/t8.json" --root "$repo" --state-dir "$state" --base HEAD --no-worktree >"$d/o" 2>&1; rc=$?; set -e
[[ $rc == 3 ]] && grep -q "SKIPPED no acceptance criteria" "$d/o" || fail "29: an issue without criteria must be SKIPPED (rc=$rc)"
[[ "$(ls "$state/ticket" | wc -l)" == 1 ]] || fail "29: a skipped issue must not create a run dir"
"$tk" init --ticket-file "$d/t7.json" --root "$repo" --state-dir "$state" --base HEAD >/dev/null
[[ -d "$d/yukta-issue-7" ]] || fail "init: worktree not created"
set +e; "$tk" init --ticket-file "$d/t7.json" --root "$repo" --state-dir "$state" --base HEAD >"$d/o" 2>&1; rc=$?; set -e
[[ $rc == 3 ]] && grep -q "SKIPPED branch" "$d/o" || fail "init: existing branch must be SKIPPED"

# --- route
"$tk" route "$run" >/dev/null
r() { jq -r "$1" "$run/routing.json"; }
[[ "$(r .complexity)/$(r .risk)/$(r .tests_mode)/$(r .models.architect.model)/$(r .models.implementer.model)" == S/low/implementer_writes/sonnet/haiku ]] || fail "route: S/low feature"
[[ "$(r .models.test_writer)" == null ]] || fail "route: no test writer for implementer_writes"
route_of() { # title labels body -> "complexity risk tests_mode architect"
  mkticket 90 "$1" "$2" "$3"; rm -rf "$d/rs"; "$tk" init --ticket-file "$d/t90.json" --root "$repo" --state-dir "$d/rs" --no-worktree >"$d/o"
  local rd; rd="$(sed -n 's/^RUN_DIR=//p' "$d/o")"; "$tk" route "$rd" >/dev/null; jq -r '[.complexity,.risk,.tests_mode,.models.architect.model]|join(" ")' "$rd/routing.json"; }
six=$'## Acceptance criteria\n- a\n- b\n- c\n- d\n- e\n- f'
[[ "$(route_of 'fix(core): x' '["core"]' "$body")" == "S low repro_only sonnet" ]] || fail "route: bug is repro_only"
[[ "$(route_of 'docs: x' '["core"]' "$body")" == "S low existing sonnet" ]] || fail "route: docs is existing"
[[ "$(route_of 'feat(core): x' '["core"]' "$six")" == "L low cases_to_code opus" ]] || fail "route: six criteria is L, opus"
[[ "$(route_of 'feat(core): x' '["core","risk"]' "$body")" == "S high cases_to_code opus" ]] || fail "route: risk label is high"
[[ "$(route_of 'feat: x' '["core","ui"]' "$body")" == "M high cases_to_code opus" ]] || fail "route: two modules is high"
[[ "$(route_of 'feat: x' '[]' "$body")" == "S medium cases_to_code sonnet" ]] || fail "route: unknown module is medium"

# --- validate-spec
cat >"$run/spec/scaffold.patch" <<'P'
diff --git a/core/src/main/java/A.java b/core/src/main/java/A.java
new file mode 100644
--- /dev/null
+++ b/core/src/main/java/A.java
@@ -0,0 +1 @@
+class A {}
P
cat >"$d/base.json" <<'J'
{"spec_version":1,"ticket_id":"7","objective":"Limit","risk":"low","assumptions":[],
 "scope":{"allowed_globs":["core/src/**","web/**","config/**","plus/**"],"forbidden_globs":[],"forbidden_imports":[],"allow_new_dependencies":false},
 "acceptance_criteria":[{"id":"AC1","text":"t","verification":{"kind":"command","command":"yk gradle :core:test --tests com.infenia.ATest"}}],
 "tests_mode":"implementer_writes","locked_files":[],"scaffold_patch":"spec/scaffold.patch",
 "test_cases":[{"id":"T1","title":"t","given":"g","when":"w","expect":"e","kind":"unit","critical":false}],
 "steps":[{"id":"S1","title":"A","depends_on":[],"covers":["T1"],"complexity":"standard","write_files":["core/src/main/java/A.java"],
   "read_files":[{"path":"core/src/main/java/B.java","lines":"1-40"}],"instructions":["do"],"contracts":[],"constraints":[],"non_goals":[],
   "done_when":{"commands":["yk gradle :core:test --tests com.infenia.ATest"]},"limits":{"max_diff_lines":100,"max_files":2}}]}
J
S2='{"id":"S2","title":"B","depends_on":[],"covers":[],"complexity":"trivial","write_files":["web/W.java"],"read_files":[],"instructions":["do"],"contracts":[],"constraints":[],"non_goals":[],"done_when":{"commands":["yk gradle :web:test --tests com.infenia.WTest"]},"limits":{"max_diff_lines":100,"max_files":2}}'
check() { # name expect(ok|text) jq-filter [ticket-jq]
  jq "$3" "$d/base.json" >"$run/spec/spec.json"
  set +e; out="$("$tk" validate-spec "$run" --worktree "$repo" 2>&1)"; rc=$?; set -e
  if [[ "$2" == ok ]]; then [[ $rc == 0 ]] || fail "$1: expected valid, got: $out"
  else [[ $rc == 1 ]] && grep -q "$2" <<<"$out" || fail "$1: expected error '$2', got rc=$rc: $out"; fi; }
check base ok '.'
[[ -s "$run/spec/spec.hash" ]] || fail "a valid spec must get a spec.hash"
check "5 missing read file" "does not exist" '.steps[0].read_files[0].path="core/src/main/java/Nope.java"'
check "read lines out of range" "not within 1-50" '.steps[0].read_files[0].lines="1-99"'
check "18 tests_mode differs" "differs from routing" '.tests_mode="cases_to_code"'
check "19 unordered steps share a file" "both write" ".steps += [$S2] | .steps[1].write_files=[\"core/src/main/java/A.java\"] | .steps[1].covers=[\"T1\"] | .steps[1].done_when.commands=[\"yk gradle :core:test --tests com.infenia.ATest\"]"
check "22 adjacent tiny step" "tiny step" ".steps += [$S2] | .steps[1].depends_on=[\"S1\"] | .steps[1].write_files=[\"core/src/main/java/A.java\"] | .steps[1].covers=[\"T1\"] | .steps[1].limits.max_diff_lines=10"
check "24 guard path" "guard path" '.steps[0].write_files=["config/checkstyle/checkstyle.xml"]'
check "write outside scope" "outside scope" '.steps[0].write_files=["boot/X.java"]'
check "not a template" "not a project command" '.steps[0].done_when.commands=["rm -rf /"]'
check "command injection" "not a project command" '.steps[0].done_when.commands=["yk gradle :core:test --tests A; rm -rf /"]'
check "uncovered case" "covered by no step" '.steps[0].covers=[]|.steps[0].done_when.commands=["yk gradle :core:test --tests X"]|.test_cases+=[{"id":"T2","title":"t","given":"g","when":"w","expect":"e","kind":"unit","critical":false}]'
check "not verifiable" "not independently verifiable" '.steps[0].covers=[]|.test_cases=[]|.steps[0].done_when.commands=["yk gradle :core:compileJava :core:compileTestJava"]'
check "three steps for S" "exceed the 2 allowed" ".steps += [$S2, $S2] | .steps[1].id=\"S3\" | .steps[2].id=\"S4\" | .steps[1].depends_on=[\"S1\"] | .steps[2].depends_on=[\"S1\"] | .steps[2].write_files=[\"web/Z.java\"]"
check "cycle" "cycle" ".steps += [$S2] | .steps[0].depends_on=[\"S2\"] | .steps[1].depends_on=[\"S1\"]"
check "unknown dependency" "unknown S9" '.steps[0].depends_on=["S9"]'
check "module order: consumer first is rejected" "order the provider first" ".steps += [$S2] | .steps[0].depends_on=[\"S2\"]"
check "module order: provider first is fine" ok ".steps += [$S2] | .steps[1].depends_on=[\"S1\"]"
check "locked file written" "locked file" '.locked_files=["core/src/main/java/A.java"]'
check "ticket id" "ticket_id" '.ticket_id="99"'
check "bad schema" "schema" 'del(.steps)'
check "step touching two modules" "more than one module" '.steps[0].write_files+=["web/W.java"]'
echo "bad patch" >"$run/spec/scaffold.patch"; : >/dev/null
check "scaffold that does not apply" "does not apply" '.'
cat >"$run/spec/scaffold.patch" <<'P'
diff --git a/core/src/main/java/Z.java b/core/src/main/java/Z.java
new file mode 100644
--- /dev/null
+++ b/core/src/main/java/Z.java
@@ -0,0 +1 @@
+class Z {}
P
check "scaffold touching a file no step writes" "stubs only" '.'
cat >"$run/spec/scaffold.patch" <<'P'
diff --git a/core/src/main/java/A.java b/core/src/main/java/A.java
new file mode 100644
--- /dev/null
+++ b/core/src/main/java/A.java
@@ -0,0 +1 @@
+class A {}
P
: >"$run/spec/empty.patch"
check "high-risk path raises risk" ok '.steps[0].write_files=["plus/X.java"]|.steps[0].read_files=[]|.scaffold_patch="spec/empty.patch"'
[[ "$(jq -r .risk "$run/routing.json")" == high ]] || fail "a write in a high-risk path must raise routing.risk"
echo "PASS ticketkit init, route and validate-spec"
