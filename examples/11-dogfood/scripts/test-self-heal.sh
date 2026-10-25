#!/usr/bin/env bash
# Checks the generated `check` workflow.
#   test-self-heal.sh            offline: renders the workflow, unscoped and scoped, and
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

render() { # module-list extras
  render_session "$here/../session.json" /p \
    | if [[ -n "$1" ]]; then scope_to_modules $1; else cat; fi \
    | heal_workflow "$policy" "$1" /p /p/heal.sh /p/.yukta-dev/heal/s "${2:-[]}"
}

fail() { echo "FAIL $*" >&2; exit 1; }

# assert LABEL JQ-FILTER: the filter must print true for the rendered session in $out
assert() { jq -e "$2" <<<"$out" >/dev/null || fail "$scope: $1"; }

# check LABEL MODULES [EXTRAS]: the shape every rendering of check must have
check() {
  scope="$1"
  out="$(render "$2" "${3:-[]}")"
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
  assert "the stages that have a policy hand over to heal, and nothing else is a fixer" '.workflows.check.nodes as $n | ([$n[] | select(.type == "SUB_WORKFLOW") | .nodeId] | sort)
    == ((["compile.heal", "coverage.heal", "spotless.heal", "tests.heal"] + (if ($n | any(.nodeId == "analyzers.gate")) then ["analyzers.heal"] else [] end)) | sort)'
  assert "the join outlasts the slowest analyzer" '[.workflows.check.nodes[] | select(.nodeId == "analyzers.join") | .config.timeoutSeconds >= 3600] | all'
  assert "the join waits for every analyzer" '.workflows.check.nodes as $n
    | ([$n[] | select(.nodeId == "analyzers.join")] | first // null) as $j
    | $j == null or $j.config.size == ([$n[] | select(.type == "PROCESS_EXECUTOR" and .config.command[1] == "run"
        and (.nodeId | test("\\.") | not) and (.nodeId | IN("spotless", "compile", "tests", "coverage") | not))] | length)'
  echo "ok   $scope: $(jq -r '.workflows.check | "\(.nodes | length) nodes, \(.edges | length) edges"' <<<"$out")"
}

check "all" "" "$all_extras"
check "core" "core"
check "core web" "core web"


# ---- a failing stage hands over to the heal workflow -------------------------------------

out="$(render "" "$all_extras")"
scope="check"
assert "licenses and security fail the run at once" '[.workflows.check.nodes[] | select(.nodeId == "licenses" or .nodeId == "security")]
  | length == 2 and all(.config.failureMode == "ERROR")'
assert "stage order" '[.workflows.check.nodes[] | select(.nodeId | test("\\.run1$")) | .nodeId]
  == ["spotless.run1", "compile.run1", "tests.run1", "coverage.run1"]'
assert "the gate routes on a failed analyzer" '.workflows.check.nodes[] | select(.nodeId == "analyzers.gate") | .config.cases | keys[0] | test("success.*false")'
assert "the extra stages are analyzers with their own tool" '[.workflows.check.nodes[] | select(.nodeId == "ui" or .nodeId == "docs" or .nodeId == "reuse" or .nodeId == "actionlint")
  | .config.command[1] == "run"] | length == 4 and all'
assert "heal knows how to run an extra stage again" '.workflows.check.nodes[] | select(.nodeId == "analyzers.heal") | .config.payload.analyzers
  | test("(^|\n)ui=./gradlew :ui:pnpmLint") and test("(^|\n)docs=\u0027sh\u0027 \u0027-c\u0027") and test("(^|\n)reuse=\u0027reuse\u0027 \u0027lint\u0027")'
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

# ---- the shared dev session: the issue run (method C) goes nav -> implement -> check, and the fixers are fresh sessions
out="$(here="$here/.." dev_session /p/heal.sh /p/issue.sh)"
scope="dev session"
assert "no resume: no check-impl, no -nav variants, no implSession" '(.workflows | keys | any(test("check-impl|-nav"))) | not'
assert "the issue run indexes the worktree, implements, then runs the self-healing check" '.workflows["implement-issue"] as $w
  | ($w.nodes | map(.nodeId)) == ["trigger", "claim", "worktree", "brief", "before-implement", "nav", "implement", "check", "before-pr", "pr", "done"]
    and ($w.nodes[] | select(.nodeId == "check") | .config.workflowId == "check" and .config.payload.issueState == "${start.repo}/.yukta-dev/issue-${start.issue}")'
assert "the solo run still checks without healing" '.workflows["solo-issue"].nodes[] | select(.nodeId == "check") | .config.workflowId == "check-plain"'
assert "heal and fix carry the issue state, so fixers keep to the issue budget and see the same index" \
  '.workflows["fix-from-low"].nodes[] | select(.nodeId == "fix.low") | .config.env.YUKTA_IMPL_COSTFILE == "${start.issueState}/implement.json"'
assert "a stage of check passes the issue state on to heal" '.workflows.check.nodes[] | select(.nodeId == "tests.heal") | .config.payload.issueState == "${start.issueState}"'
assert "the epic does not make session ids" '(.workflows | tojson | contains("implSession")) | not'
echo "ok   dev session"

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
echo "ok   heal and fix workflows"

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
