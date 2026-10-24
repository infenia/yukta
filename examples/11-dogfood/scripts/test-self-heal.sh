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
  assert "the join waits for every analyzer" '.workflows.check.nodes as $n
    | ([$n[] | select(.nodeId == "analyzers.join")] | first // null) as $j
    | $j == null or $j.config.size == ([$n[] | select(.type == "PROCESS_EXECUTOR" and .config.command[1] == "run"
        and (.nodeId | test("\\.") | not) and (.nodeId | IN("spotless", "compile", "tests", "coverage") | not))] | length)'
  echo "ok   $scope: $(jq -r '.workflows.check | "\(.nodes | length) nodes, \(.edges | length) edges"' <<<"$out")"
}

# 3 ladder rungs; spotless adds the auto rung
check "all" "" "" 3 "$all_extras"
check "core" "core" "" 3
check "core web" "core web" "" 3
check "ladder sonnet,opus" "" "sonnet,opus" 2

out="$(render "" "" "$all_extras")"
scope=all
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
  | test("ui=\u0027sh\u0027 \u0027-c\u0027") and test("(^|\n)reuse=\u0027reuse\u0027 \u0027lint\u0027")'

# --pause before-opus: a WAIT_FOR_SIGNAL stands in front of every opus fixer, and only there
out="$(render "" "" "$all_extras" before-opus)"
scope="all (paused)"
assert "a pause before every opus fixer" '[.workflows.check.nodes[] | select(.type == "WAIT_FOR_SIGNAL") | .nodeId] | sort
  == ["analyzers.pause", "compile.pause", "coverage.pause", "spotless.pause", "tests.pause"]'
assert "the opus fixer is only reached through its pause" '.workflows.check.edges as $e
  | ["spotless", "compile", "tests", "coverage", "analyzers"] | all(. as $s
      | [$e[] | select(.target == $s + ".f" + (if $s == "spotless" then "4" else "3" end)) | .source] == [$s + ".pause"])'
assert "the lower rungs skip the pause" '.workflows.check.edges | all(.target != "compile.f1" or .source != "compile.pause")'
[[ "$(node_stage compile.pause)" == compile && "$(node_stage analyzers.r2)" == analyzers && "$(node_stage verify)" == verify ]] || fail "node_stage"
echo "ok   details"

[[ "${1:-}" == --online ]] || exit 0

# ---- online: the whole workflow on the dev server, with a fake gradlew and a fake claude --------------

dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
proj="$dir/proj"
mkdir -p "$proj"
cat >"$proj/gradlew" <<'SH'
#!/usr/bin/env bash
# fake gradle: a task fails while the file fail-<task> exists; spotlessApply formats
rc=0
for t in "$@"; do
  case "$t" in -*) continue ;; esac
  if [[ "$t" == spotlessApply ]]; then rm -f fail-spotlessCheck; echo formatted >>src.txt; continue; fi
  if [[ -e "fail-$t" ]]; then echo "error: $t failed (violation)" >&2; rc=1; fi
done
exit $rc
SH
cat >"$dir/claude" <<'SH'
#!/usr/bin/env bash
# fake claude: fixes the failing tasks only when run with the model named in .fake-fix-at
prompt="" model=""
while (($#)); do case "$1" in -p) prompt="$2"; shift 2 ;; --model) model="$2"; shift 2 ;; *) shift ;; esac; done
if [[ "$model" == "$(cat .fake-fix-at 2>/dev/null)" ]]; then
  for t in $(sed -n 's/^Gradle tasks: //p' <<<"$prompt"); do rm -f "fail-$t"; done
  echo "fix by $model" >>fixed.txt
  echo '{"total_cost_usd":0.01,"usage":{"input_tokens":10,"cache_creation_input_tokens":5,"cache_read_input_tokens":100,"output_tokens":3},"structured_output":{"fixed":true,"summary":"fake fix"}}'
else
  echo '{"total_cost_usd":0.005,"structured_output":{"fixed":false,"summary":"cannot fix"}}'
fi
SH
chmod +x "$proj/gradlew" "$dir/claude"
git -C "$proj" init -q
git -C "$proj" -c user.email=t@t -c user.name=t add -A
git -C "$proj" -c user.email=t@t -c user.name=t commit -qm init
export YUKTA_CLAUDE_BIN="$dir/claude"
yk="$here/../yk"

scenario() { # label fail-files fix-at expected-pattern expected-exit [yk check flags]
  local label="$1" files="$2" at="$3" pattern="$4" want="$5" result rc=0 f
  rm -f "$proj"/fail-* "$proj/.fake-fix-at" "$proj/fixed.txt"
  for f in $files; do touch "$proj/fail-$f"; done
  [[ -z "$at" ]] || echo "$at" >"$proj/.fake-fix-at"
  # shellcheck disable=SC2086
  result="$("$yk" check -p "$proj" ${6:-} 2>&1)" || rc=$?
  tail -1 <<<"$result" | grep -Eq "$pattern" && ((rc == want)) \
    || { tail -15 <<<"$result" >&2; fail "online $label: expected /$pattern/ and exit $want, got exit $rc"; }
  echo "ok   online $label: $(tail -1 <<<"$result" | cut -d' ' -f2-4)"
}

scenario "clean run" "" "" 'RESULT status=SUCCESS failed_node=- healed=- ' 0
scenario "spotless healed by the deterministic fix" "spotlessCheck" "" 'status=SUCCESS .*healed=spotless:auto ' 0
scenario "an analyzer healed at the sonnet rung" "checkstyleMain" "sonnet" 'status=SUCCESS .*healed=analyzers:sonnet ' 0
scenario "a compile error healed at the opus rung" "compileJava" "opus" 'status=SUCCESS .*healed=compile:opus ' 0
YUKTA_PAUSE_SECONDS=2 scenario "a compile error healed at the opus rung behind the before-opus pause" "compileJava" "opus" 'status=SUCCESS .*healed=compile:opus ' 0 "--pause before-opus"
scenario "licenses fail the run at once" "licensee" "" 'status=FAILURE failed_node=licenses ' 1
scenario "an unfixable test failure" "test" "" 'status=FAILURE failed_node=tests ' 1
scenario "an unfixable analyzer" "spotbugsMain" "" 'status=FAILURE failed_node=spotbugs ' 1
echo "ok   online"
