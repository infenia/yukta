#!/usr/bin/env bash
# Checks the generated `check` workflow.
#   test-self-heal.sh            offline: renders the workflow, unscoped, scoped and with another ladder, and
#                                asserts its shape (no server needed)
#   test-self-heal.sh --online   also runs it on the dev server against a throwaway project with a fake
#                                gradlew and a fake claude, and asserts the RESULT of each scenario
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/_lib.sh"
need jq
policy="$(cat "$here/../heal.json")"

# every extra stage with its first variant, as if all the tools were installed
all_extras="$(jq -c '[.extra | to_entries[] | {nodeId: .key, type: "PROCESS_EXECUTOR", config: {command: .value.variants[0].command}}]' <<<"$policy")"

render() { # module-list ladder extras pauses
  render_session "$here/../session.json" /p \
    | if [[ -n "$1" ]]; then scope_to_modules $1; else cat; fi \
    | heal_workflow "$policy" "$1" /p /p/heal.sh /p/.yukta-dev/heal/s "${2:-}" "${3:-[]}" "${4:-}"
}

fail() { echo "FAIL $*" >&2; exit 1; }

# assert LABEL JQ-FILTER: the filter must print true for the rendered session in $out
assert() { jq -e "$2" <<<"$out" >/dev/null || fail "$scope: $1"; }

check() { # label module-list ladder number-of-ladder-rungs
  scope="$1"
  local rungs="${4:?}"
  out="$(render "$2" "$3" "${5:-[]}")"
  assert "edges name existing nodes" '.workflows.check as $w | ($w.nodes | map(.nodeId)) as $ids
    | ($ids | length) == ($ids | unique | length)
      and ($w.edges | all(.source as $s | .target as $t | ($ids | index($s)) != null and ($ids | index($t)) != null))'
  assert "a sourcePort only leaves a routed node or the gate" '.workflows.check
    | (.nodes | map({(.nodeId): .}) | add) as $n
    | .edges | all(.sourcePort == null or $n[.source].config.routeByExitCode == true or $n[.source].type == "BRANCH")'
  assert "a routed node continues on failure; the others fail the run" '[.workflows.check.nodes[]
    | select(.type == "PROCESS_EXECUTOR")
    | if .config.routeByExitCode == true then .config.failureMode == "CONTINUE" else .config.failureMode == "ERROR" end] | all'
  assert "an acyclic graph" '.workflows.check as $g | ($g.nodes | map(.nodeId)) as $ids
    | def kahn: reduce range(0; $ids | length) as $_ ({left: $ids, edges: $g.edges};
          (.edges | map(.target)) as $t
          | .left as $l | ([ $l[] | select(. as $x | $t | index($x) | not) ]) as $free
          | .left -= $free | .edges |= map(select(.source as $s | $free | index($s) | not)))
      | .left | length == 0;
    kahn'
  assert "one trigger, one verify, one done; the run starts at the first stage" '.workflows.check as $w
    | ([$w.nodes[] | select(.nodeId == "verify" or .nodeId == "done" or .nodeId == "trigger")] | length) == 3
      and ($w.edges | any(.source == "trigger" and (.target | endswith(".run1"))))'
  assert "gradle-check is replaced by check" '.workflows | has("check") and (has("gradle-check") | not)'
  # every sequential stage: one fixer per rung, one more run than fixers, only the last run fails the run
  jq -e --argjson policy "$policy" --argjson lad "$rungs" '
    .workflows.check.nodes as $nodes
    | ($nodes | map(.nodeId)) as $ids
    | [$ids[] | select(test("\\.run1$")) | sub("\\.run1$"; "")] as $stages
    | ($stages | length > 0)
      and ($stages | all(. as $s
        | ($policy.stages[$s] // {}) as $p
        | ((if $p.auto then 1 else 0 end) + (if $p.claude then $lad else 0 end)) as $k
        | ([$ids[] | select(test("^" + $s + "\\.f[0-9]+$"))] | length) == $k
          and ([$ids[] | select(test("^" + $s + "\\.run[0-9]+$"))] | length) == $k + 1
          and ($nodes[] | select(.nodeId == $s + ".run" + (($k + 1) | tostring)) | .config.failureMode) == "ERROR"))' <<<"$out" >/dev/null \
    || fail "$scope: chain shape"
  jq -e --argjson lad "$rungs" '
    .workflows.check.nodes as $nodes
    | ([$nodes[] | select(.nodeId | test("^analyzers\\.f[0-9]+$"))] | length) as $f
    | ([$nodes[] | select(.nodeId | test("^analyzers\\.r[0-9]+$"))] | length) as $r
    | if ([$nodes[] | select(.nodeId == "analyzers.join")] | length) == 0 then true
      elif ([$nodes[] | select(.nodeId == "analyzers.gate")] | length) == 0 then ($f == 0 and $r == 0)
      else $f == $lad and $r == $lad
           and ($nodes[] | select(.nodeId == "analyzers.r" + ($lad | tostring)) | .config.failureMode) == "ERROR" end' <<<"$out" >/dev/null \
    || fail "$scope: analyzer chain"
  assert "the join outlasts the slowest analyzer" '[.workflows.check.nodes[] | select(.nodeId == "analyzers.join") | .config.timeoutSeconds >= 3600] | all'
  assert "the join waits for every analyzer" '.workflows.check.nodes as $n
    | ([$n[] | select(.nodeId == "analyzers.join")] | first // null) as $j
    | $j == null or $j.config.size == ([$n[] | select(.type == "PROCESS_EXECUTOR" and .config.command[1] == "run"
        and (.nodeId | test("\\.") | not) and (.nodeId | IN("spotless", "compile", "tests", "coverage") | not))] | length)'
  echo "ok   $scope: $(jq -r '.workflows.check | "\(.nodes | length) nodes, \(.edges | length) edges"' <<<"$out")"
}

# the unrolled chain, which check-impl still uses (a ladder is given): 3 ladder rungs; spotless adds the auto rung
three=haiku,sonnet,opus
check "unrolled all" "" "$three" 3 "$all_extras"
check "unrolled core" "core" "$three" 3
check "unrolled core web" "core web" "$three" 3
check "unrolled ladder sonnet,opus" "" "sonnet,opus" 2

out="$(render "" "$three" "$all_extras")"
scope="unrolled all"
assert "licenses and security fail the run at once" '[.workflows.check.nodes[] | select(.nodeId == "licenses" or .nodeId == "security")]
  | length == 2 and all(.config.failureMode == "ERROR")'
assert "stage order" '[.workflows.check.nodes[] | select(.nodeId | test("\\.run1$")) | .nodeId]
  == ["spotless.run1", "compile.run1", "tests.run1", "coverage.run1"]'
assert "the ladder models are in the fixers" '[.workflows.check.nodes[] | select(.nodeId == "compile.f1" or .nodeId == "compile.f3") | .config.command[4]]
  == ["haiku", "opus"] and (.workflows.check.nodes[] | select(.nodeId == "spotless.f1") | .config.command[4]) == "auto"'
assert "the gate routes on a failed analyzer" '.workflows.check.nodes[] | select(.nodeId == "analyzers.gate") | .config.cases | keys[0] | test("success.*false")'
assert "the extra stages are analyzers with their own tool" '[.workflows.check.nodes[] | select(.nodeId == "ui" or .nodeId == "docs" or .nodeId == "reuse" or .nodeId == "actionlint")
  | .config.command[1] == "run"] | length == 4 and all'
assert "the rerun knows how to run an extra stage" '.workflows.check.nodes[] | select(.nodeId == "analyzers.r1") | .config.env.YUKTA_HEAL_ANALYZERS
  | test("(^|\n)ui=./gradlew :ui:pnpmLint") and test("(^|\n)docs=\u0027sh\u0027 \u0027-c\u0027") and test("(^|\n)reuse=\u0027reuse\u0027 \u0027lint\u0027")'

# --pause before-opus: a WAIT_FOR_SIGNAL stands in front of every opus fixer, and only there
out="$(render "" "$three" "$all_extras" before-opus)"
scope="unrolled all (paused)"
assert "a pause before every opus fixer" '[.workflows.check.nodes[] | select(.type == "WAIT_FOR_SIGNAL") | .nodeId] | sort
  == ["analyzers.pause", "compile.pause", "coverage.pause", "spotless.pause", "tests.pause"]'
assert "the opus fixer is only reached through its pause" '.workflows.check.edges as $e
  | ["spotless", "compile", "tests", "coverage", "analyzers"] | all(. as $s
      | [$e[] | select(.target == $s + ".f" + (if $s == "spotless" then "4" else "3" end)) | .source] == [$s + ".pause"])'
assert "the lower rungs skip the pause" '.workflows.check.edges | all(.target != "compile.f1" or .source != "compile.pause")'
[[ "$(node_stage compile.pause)" == compile && "$(node_stage analyzers.r2)" == analyzers && "$(node_stage verify)" == verify ]] || fail "node_stage"
echo "ok   details"

# ---- the default: a failing stage hands over to the heal workflow -------------------------------------

out="$(render "" "" "$all_extras")"
scope="check"
assert "edges name existing nodes" '.workflows.check as $w | ($w.nodes | map(.nodeId)) as $ids
  | ($ids | length) == ($ids | unique | length)
    and ($w.edges | all(.source as $s | .target as $t | ($ids | index($s)) != null and ($ids | index($t)) != null))'
assert "every healable stage has a heal sub-workflow node and no fixer chain" '[.workflows.check.nodes[] | select(.nodeId | test("\\.heal$"))
  | select(.type == "SUB_WORKFLOW" and .config.workflowId == "heal")] | map(.nodeId) | sort
  == ["analyzers.heal", "compile.heal", "coverage.heal", "spotless.heal", "tests.heal"]'
assert "no claude fixer in the check itself" '[.workflows.check.nodes[] | select(.nodeId | test("\\.f[0-9]+$"))] | map(.nodeId) == ["spotless.f1"]'
assert "a failed run goes to heal, and heal carries on with the next stage" '.workflows.check.edges
  | any(.source == "compile.run1" and .target == "compile.heal" and .sourcePort == "failure")
    and any(.source == "compile.heal" and .target == "tests.run1")'
assert "spotless: the deterministic fix first, then heal" '.workflows.check.edges
  | any(.source == "spotless.f1" and .target == "spotless.run2" and .sourcePort == "success")
    and any(.source == "spotless.f1" and .target == "spotless.heal" and .sourcePort == "failure")
    and any(.source == "spotless.run2" and .target == "spotless.heal" and .sourcePort == "failure")'
assert "heal is told the stage and where the records are" '.workflows.check.nodes[] | select(.nodeId == "tests.heal")
  | .config.payload | .stage == "tests" and .healDir == "/p/.yukta-dev/heal/s" and .project == "/p"'
assert "the analyzers hand the failing ones to one heal" '.workflows.check.edges | any(.source == "analyzers.gate" and .target == "analyzers.heal" and .sourcePort == "fail")'
[[ "$(node_stage compile.heal)" == compile && "$(node_stage analyzers.heal)" == analyzers ]] || fail "node_stage heal"
echo "ok   check hands over to heal"

heal="$(heal_subflows "$policy" /p/heal.sh)"
[[ "$(jq -r '.workflows | keys | join(",")' <<<"$heal")" == fix-from-high,fix-from-low,fix-from-medium,heal ]] || fail "heal workflows"
hassert() { jq -e "$2" <<<"$heal" >/dev/null || fail "heal: $1"; }
hassert "edges name existing nodes" '.workflows | all(. as $w | ($w.nodes | map(.nodeId)) as $ids
  | ($ids | length) == ($ids | unique | length) and ($w.edges | all(.source as $s | .target as $t | ($ids | index($s)) != null and ($ids | index($t)) != null)))'
hassert "triage is a System One CLASSIFY routed on the label, with a port per difficulty" '.workflows.heal.nodes[] | select(.nodeId == "triage")
  | .type == "CLASSIFY" and .config.routeByLabel == true and (.config.backend // "SYSTEMONE") == "SYSTEMONE"
    and (.config.labels | keys | sort) == ["average", "easy", "hard", "moderate", "simple", "trivial", "veryhard"]'
hassert "each difficulty starts at its model and effort" '.workflows.heal.edges as $e
  | {trivial: "fix-haiku-low", easy: "fix-haiku-medium", simple: "fix-haiku-high", moderate: "fix-sonnet-low",
     average: "fix-sonnet-medium", hard: "fix-sonnet-high", veryhard: "fix-opus-low", uncertain: "fix-sonnet-medium", error: "fix-sonnet-medium"}
    | to_entries | all(. as $c | $e | any(.source == "triage" and .sourcePort == $c.key and .target == $c.value))'
hassert "a failed model hands over to the next one at low effort; the last gives up" '.workflows.heal.edges as $e
  | ($e | any(.source == "fix-haiku-high" and .target == "fix-sonnet-low" and .sourcePort == "failure"))
    and ($e | any(.source == "fix-sonnet-high" and .target == "fix-opus-low" and .sourcePort == "failure"))
    and ($e | any(.source == "fix-sonnet-medium" and .target == "fix-opus-low" and .sourcePort == "failure"))
    and ($e | any(.source == "fix-opus-low" and .target == "giveup" and .sourcePort == "failure"))'
hassert "a fixed stage ends the heal" '.workflows.heal.edges | all(.sourcePort != "success" or .target == "done")'
hassert "giveup fails the heal" '.workflows.heal.nodes[] | select(.nodeId == "giveup") | .config.failureMode == "ERROR"'
hassert "a fix rung calls the fix workflow of its effort with its model" '.workflows.heal.nodes[] | select(.nodeId == "fix-sonnet-medium")
  | .config.workflowId == "fix-from-medium" and .config.payload.model == "sonnet" and .config.routeByOutcome == true'
hassert "fix climbs low, medium, high and re-checks after each" '.workflows["fix-from-low"] as $w
  | ($w.nodes | map(.nodeId)) == ["trigger", "fix.low", "check.low", "fix.medium", "check.medium", "fix.high", "check.high", "done"]
    and ($w.edges | any(.source == "check.low" and .target == "fix.medium" and .sourcePort == "failure"))
    and ($w.edges | any(.source == "check.high" and .target == "done" and .sourcePort == null))
    and ($w.nodes[] | select(.nodeId == "check.high") | .config.failureMode == "ERROR")'
hassert "fix from medium has no low rung" '.workflows["fix-from-medium"].nodes | map(.nodeId) | index("fix.low") == null and index("fix.medium") != null'
hassert "each effort is time-boxed and budgeted" '.workflows["fix-from-low"].nodes
  | [.[] | select(.nodeId | startswith("fix.")) | .config.timeoutSeconds] == [600, 1200, 1800]
    and ([.[] | select(.nodeId == "fix.high") | .config.env.YUKTA_HEAL_BUDGET] == ["2"])'
hassert "the fixer is told model, stage and effort" '.workflows["fix-from-low"].nodes[] | select(.nodeId == "fix.medium")
  | .config.command == ["/p/heal.sh", "fix", "${start.stage}", "0", "${start.model}", "--effort", "medium"]'
hassert "the triage call waits less than the 60 seconds Ollama allows, more than the 35 of the default" '.workflows.heal.nodes[] | select(.nodeId == "triage") | .config.timeout > 35 and .config.timeout < 60'
hassert "triage asks the long question and describes each level with its signals" '.workflows.heal.nodes[] | select(.nodeId == "triage")
  | (.config.question | length) > 300 and ([.config.labels[] | length] | min) > 100'
echo "ok   heal and fix workflows"

# ---- brief: what the triage model reads
bd="$(mktemp -d)"; trap 'rm -rf "$bd"' EXIT
git init -q "$bd/r"; (cd "$bd/r" && git config user.email t@t && git config user.name t && echo a >A.java && git add -A && git commit -q -m x && echo b >>A.java && echo n >New.java)
mkdir -p "$bd/heal"
printf './gradlew test -q' >"$bd/heal/tests.cmd"
printf '> Task :core:test FAILED\nOrderServiceTest > shouldApplyDiscount() FAILED\n    AssertionFailedError: expected: <90> but was: <100>\n        at OrderServiceTest.java:48\n1 test failed\n' >"$bd/heal/tests.run.out"
brief="$(cd "$bd/r" && YUKTA_HEAL_DIR="$bd/heal" bash "$here/yukta-heal.sh" brief tests)"
for want in "stage: tests" "command: ./gradlew test" "FAILED" "files changed so far: 2" "changed files: A.java (+1 -0), New.java (new)" "at OrderServiceTest.java:48"; do
  grep -qF -- "$want" <<<"$brief" || fail "brief lacks '$want': $brief"
done
for want in "---" "log (the end):"; do grep -qxF -- "$want" <<<"$brief" || fail "brief lacks the line '$want': $brief"; done
big="$(for i in $(seq 1 400); do echo "error: line $i $(printf 'x%.0s' $(seq 1 200))"; done; echo "BUILD FAILED: the real cause is at the end")"
printf '%s\n' "$big" >"$bd/heal/tests.run.out"
brief="$(cd "$bd/r" && YUKTA_HEAL_DIR="$bd/heal" bash "$here/yukta-heal.sh" brief tests)"
[[ "${#brief}" -le 5000 ]] || fail "brief is over 5000 characters: ${#brief}"
grep -qF "BUILD FAILED: the real cause is at the end" <<<"$brief" || fail "the end of the log was cut off, not the start"
echo "ok   brief"

heal="$(YUKTA_TRIAGE=off heal_subflows "$policy" /p/heal.sh)"
hassert "no triage when it is off: brief goes straight to the default level" '.workflows.heal | ([.nodes[] | select(.nodeId == "triage")] | length) == 0
  and (.edges | any(.source == "brief" and .target == "fix-sonnet-medium"))'
heal="$(heal_subflows "$policy" /p/heal.sh before-opus)"
hassert "a pause stands in front of every way into opus" '.workflows.heal.edges
  | [.[] | select(.target == "fix-opus-low") | .source] == ["pause-opus"]
    and any(.source == "fix-sonnet-high" and .target == "pause-opus" and .sourcePort == "failure")
    and any(.source == "triage" and .target == "pause-opus" and .sourcePort == "veryhard")'
heal="$(heal_subflows "$(policy_models "$policy" sonnet,opus)" /p/heal.sh)"
hassert "--models sonnet,opus: no haiku, and a haiku level starts at sonnet" '.workflows.heal | ([.nodes[] | select(.nodeId | test("haiku"))] | length) == 0
  and (.edges | any(.source == "triage" and .sourcePort == "trivial" and .target == "fix-sonnet-low"))'
echo "ok   heal variants"

[[ "${1:-}" == --online ]] || exit 0

# ---- online: the whole workflow on the dev server, with a fake gradlew and a fake claude --------------

dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
"$here/fake-project.sh" "$dir"
proj="$dir/proj"
export YUKTA_CLAUDE_BIN="$dir/claude" YUKTA_CHECK_EXTRAS=off
yk="$here/../yk"

scenario() { # label fail-files fix-at expected-pattern expected-exit [yk check flags]
  local label="$1" files="$2" at="$3" pattern="$4" want="$5" result rc=0 f
  rm -f "$proj"/fail-* "$proj"/slow-* "$proj/.fake-fix-at" "$proj/fixed.txt"
  for f in $files; do if [[ "$f" == slow-* ]]; then touch "$proj/$f"; else touch "$proj/fail-$f"; fi; done
  [[ -z "$at" ]] || echo "$at" >"$proj/.fake-fix-at"
  # shellcheck disable=SC2086
  result="$("$yk" check -p "$proj" ${6:-} 2>&1)" || rc=$?
  tail -1 <<<"$result" | grep -Eq "$pattern" && ((rc == want)) \
    || { tail -15 <<<"$result" >&2; fail "online $label: expected /$pattern/ and exit $want, got exit $rc"; }
  echo "ok   online $label: $(tail -1 <<<"$result" | cut -d' ' -f2-4)"
}

scenario "clean run" "" "" 'RESULT status=SUCCESS failed_node=- healed=- ' 0
scenario "spotless healed by the deterministic fix" "spotlessCheck" "" 'status=SUCCESS .*healed=spotless:auto ' 0
# no Ollama in the test: the triage is off, so a heal starts at the default level, sonnet at medium effort
export YUKTA_TRIAGE=off
scenario "an analyzer healed at the default start, sonnet medium" "checkstyleMain" "sonnet/medium" 'status=SUCCESS .*healed=analyzers:sonnet/medium ' 0
scenario "a compile error healed when the effort climbs to high" "compileJava" "sonnet/high" 'status=SUCCESS .*healed=compile:sonnet/high ' 0
scenario "a compile error healed by the next model, at low effort" "compileJava" "opus/low" 'status=SUCCESS .*healed=compile:opus/low ' 0
YUKTA_PAUSE_SECONDS=2 scenario "the same behind the before-opus pause" "compileJava" "opus/low" 'status=SUCCESS .*healed=compile:opus/low ' 0 "--pause before-opus"
scenario "--models sonnet,opus has no haiku in it" "compileJava" "opus/low" 'status=SUCCESS .*healed=compile:opus/low ' 0 "--models sonnet,opus"
# longer than the 30 s default idle timeout of a node: the join must not time out waiting for it
scenario "an analyzer slower than the default node timeout" "slow-checkstyleMain" "" 'status=SUCCESS failed_node=- healed=- ' 0
scenario "licenses fail the run at once" "licensee" "" 'status=FAILURE failed_node=licenses ' 1
scenario "an unfixable test failure: every model at every effort fails" "test" "" 'status=FAILURE failed_node=tests ' 1
scenario "an unfixable analyzer" "spotbugsMain" "" 'status=FAILURE failed_node=spotbugs ' 1
echo "ok   online"
