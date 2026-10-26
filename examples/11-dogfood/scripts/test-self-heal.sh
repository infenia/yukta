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

# ---- the shared dev session: the issue run  goes nav -> implement -> check, and the fixers are fresh sessions
out="$(here="$here/.." dev_session /p/heal.sh /p/issue.sh)"
scope="dev session"
assert "no resume, no solo or plain-check workflows, no -nav variants, no implSession" '(.workflows | keys | any(test("check-impl|check-plain|solo|-nav"))) | not'
assert "the issue run indexes the worktree, implements, then runs the self-healing check" '.workflows["implement-issue"] as $w
  | ($w.nodes | map(.nodeId)) == ["trigger", "claim", "worktree", "brief", "before-implement", "nav", "implement", "check", "before-pr", "pr", "done"]
    and ($w.nodes[] | select(.nodeId == "check") | .config.workflowId == "check" and .config.payload.issueState == "${start.repo}/.yukta-dev/issue-${start.issue}")'
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
    and (.config.labels | keys | sort) == ["average", "easy", "hard"]'
hassert "each difficulty starts at its model and effort" '.workflows.heal.edges as $e
  | {easy: "fix-haiku-medium", average: "fix-sonnet-medium", hard: "fix-sonnet-high", uncertain: "fix-sonnet-medium", error: "fix-sonnet-medium"}
    | to_entries | all(. as $c | $e | any(.source == "triage" and .sourcePort == $c.key and .target == $c.value))'
hassert "a failed model hands over to the next one at low effort; the last gives up" '.workflows.heal.edges as $e
  | ($e | any(.source == "fix-haiku-medium" and .target == "fix-sonnet-low" and .sourcePort == "failure"))
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
hassert "triage asks its question and describes each level" '.workflows.heal.nodes[] | select(.nodeId == "triage")
  | (.config.question | length) > 50 and ([.config.labels[] | length] | min) > 60'
echo "ok   heal and fix workflows"

# ---- brief: what the triage model reads
bd="$(mktemp -d)"; trap 'rm -rf "$bd"' EXIT
git init -q "$bd/r"; (cd "$bd/r" && git config user.email t@t && git config user.name t && echo a >A.java && git add -A && git commit -q -m x && echo b >>A.java && echo n >New.java)
mkdir -p "$bd/heal"
printf './gradlew test -q' >"$bd/heal/tests.cmd"
printf '> Task :core:test FAILED\nOrderServiceTest > shouldApplyDiscount() FAILED\n    AssertionFailedError: expected: <90> but was: <100>\n        at OrderServiceTest.java:48\n1 test failed\n' >"$bd/heal/tests.run.out"
brief="$(cd "$bd/r" && YUKTA_HEAL_DIR="$bd/heal" bash "$here/yukta-heal.sh" brief tests)"
for want in "stage: tests" "command: ./gradlew test" "FAILED" "files changed so far: 2" "shouldApplyDiscount"; do
  grep -qF -- "$want" <<<"$brief" || fail "brief lacks '$want': $brief"
done
! grep -qF "changed files:" <<<"$brief" || fail "the brief holds more than the problem lines: $brief"
big="$(for i in $(seq 1 400); do echo "error: line $i $(printf 'x%.0s' $(seq 1 200))"; done)"
printf '%s\n' "$big" >"$bd/heal/tests.run.out"
brief="$(cd "$bd/r" && YUKTA_HEAL_DIR="$bd/heal" bash "$here/yukta-heal.sh" brief tests)"
[[ "${#brief}" -le 1001 ]] || fail "brief is over 1000 characters: ${#brief}"
echo "ok   brief"

# ---- monitoring: every step leaves a line on the events log
mon="$(mktemp -d)"
git init -q "$mon/r"; (cd "$mon/r" && git config user.email t@t && git config user.name t && echo a >A.java && git add -A && git commit -q -m x)
printf '#!/usr/bin/env bash\nexit 0\n' >"$mon/r/gradlew"; chmod +x "$mon/r/gradlew"
cat >"$mon/claude" <<'SH'
#!/usr/bin/env bash
echo fixed >>A.java
echo '{"total_cost_usd":0.25,"usage":{"input_tokens":10,"cache_creation_input_tokens":100,"cache_read_input_tokens":900,"output_tokens":20},"structured_output":{"fixed":true,"summary":"fixed the thing"}}'
SH
chmod +x "$mon/claude"
heal() { (cd "$mon/r" && YUKTA_HEAL_DIR="$mon/heal" YUKTA_HEAL_EVENTS="${EV-$mon/events.jsonl}" YUKTA_EXECUTION_ID=exec-1 YUKTA_SESSION_ID=sess-1 YUKTA_CLAUDE_BIN="$mon/claude" \
  YUKTA_HEAL_TASKS=compileJava bash "$here/yukta-heal.sh" "$@"); }
heal run compile -- sh -c 'echo "error: Foo.java:3: boom 42"; exit 1' >/dev/null || true
heal brief compile >/dev/null
YUKTA_METADATA_CLASSIFICATION='{"label":"easy","choice":"easy","probability":0.55}' heal triage-log compile level >/dev/null
YUKTA_METADATA_FAILURE_EXCEPTION='HTTP 500 after 60s' heal triage-log compile error >/dev/null
YUKTA_METADATA_CLASSIFICATION='{choice=hard, probability=0.7, probabilities={moderate=0.05, hard=0.7}, label=hard, uncertain=false}' heal triage-log compile level >/dev/null
heal fix compile 0 sonnet --effort medium >/dev/null || true
heal recheck compile >/dev/null || true
heal verify >/dev/null || true
heal giveup compile >/dev/null || true
ev="$mon/events.jsonl"
[[ "$(wc -l <"$ev")" == 9 ]] || fail "events: $(cat "$ev")"
mevent() { jq -e -s "$2" "$ev" >/dev/null || fail "event $1: $(cat "$ev")"; }
mevent "all carry the run, session, project and version" 'all(.v == 1 and .run == "exec-1" and .session == "sess-1" and .project == "r" and (.ts | test("^20[0-9-]+T[0-9:]+Z$")))'
mevent stage '.[0] | .event == "stage" and .stage == "compile" and .ok == false and .exit == 1 and (.sig | length) == 12 and (.head | test("boom")) and .secs >= 0'
mevent brief '.[1] | .event == "brief" and .chars > 0 and .chars <= 5000'
mevent "triage level" '.[2] | .event == "triage" and .route == "level" and .label == "easy" and .p == 0.55 and .start == "haiku/medium" and .fallback == null and .secs >= 0 and .chars > 0 and (.model | length) > 0'
mevent "triage error" '.[3] | .route == "error" and .fallback == "error" and (.error | test("HTTP 500")) and .start == "sonnet/medium"'
mevent "triage from the text of a map" '.[4] | .route == "level" and .label == "hard" and .p == 0.7 and .start == "sonnet/high" and (.raw | not)'
mevent fix '.[5] | .event == "fix" and .model == "sonnet" and .effort == "medium" and .fixed == true and .claimed == true and .files == ["A.java"] and .cost == 0.25 and .usage.cache_read == 900 and .secs >= 0 and .guard == null and .summary == "fixed the thing" and .attempt == 1'
mevent recheck '.[6] | .event == "recheck" and .stage == "compile" and .ok == false'
mevent verify '.[7] | .event == "verify" and .ok == true'
mevent giveup '.[8] | .event == "giveup" and .stage == "compile"'
# the same failure hashes the same, whatever its numbers and paths
sig1="$(jq -s -r '.[0].sig' "$ev")"
heal run compile -- sh -c 'echo "error: Foo.java:99: boom 7"; exit 1' >/dev/null || true
[[ "$(jq -s -r 'last.sig' "$ev")" == "$sig1" ]] || fail "the signature changed with the numbers"
# off: no log
rm -f "$ev"; EV="" heal run compile -- true >/dev/null; [[ ! -e "$ev" ]] || fail "events were written although they are off"
echo "ok   events"

heal="$(YUKTA_TRIAGE=off heal_subflows "$policy" /p/heal.sh)"
hassert "no triage when it is off: brief goes straight to the default level" '.workflows.heal | ([.nodes[] | select(.nodeId == "triage")] | length) == 0
  and (.edges | any(.source == "brief" and .target == "fix-sonnet-medium"))'
heal="$(heal_subflows "$policy" /p/heal.sh before-opus)"
hassert "a pause stands in front of every way into opus" '.workflows.heal.edges
  | [.[] | select(.target == "fix-opus-low") | .source] == ["pause-opus"]
    and any(.source == "fix-sonnet-high" and .target == "pause-opus" and .sourcePort == "failure")
    and (any(.source == "triage" and .target == "pause-opus") | not)'
heal="$(heal_subflows "$(policy_models "$policy" sonnet,opus)" /p/heal.sh)"
hassert "--models sonnet,opus: no haiku, and the easy level starts at sonnet" '.workflows.heal | ([.nodes[] | select(.nodeId | test("haiku"))] | length) == 0
  and (.edges | any(.source == "triage" and .sourcePort == "easy" and .target == "fix-sonnet-medium"))'
heal="$(YUKTA_TRIAGE_MODEL=tev1 YUKTA_TRIAGE_URL=http://10.0.0.9:11434 heal_subflows "$policy" /p/heal.sh)"
hassert "YUKTA_TRIAGE_MODEL and YUKTA_TRIAGE_URL choose the model and host of the triage" '.workflows.heal.nodes[] | select(.nodeId == "triage")
  | .config.model == "tev1" and .config.baseUrl == "http://10.0.0.9:11434"'
hassert "the triage log nodes are told the same model and host" '[.workflows.heal.nodes[] | select(.nodeId | startswith("triage-log-")) | .config.env]
  | all(.YUKTA_TRIAGE_MODEL == "tev1" and .YUKTA_TRIAGE_URL == "http://10.0.0.9:11434")'
heal="$(heal_subflows "$policy" /p/heal.sh)"
hassert "without them the triage model comes from heal.json" ".workflows.heal.nodes[] | select(.nodeId == \"triage\") | .config.model == \"$(jq -r .triage.model <<<"$policy")\""
echo "ok   heal variants"

[[ "${1:-}" == --online ]] || exit 0

# ---- online: the whole workflow on the dev server, with a fake gradlew and a fake claude --------------

dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
"$here/fake-project.sh" "$dir"
proj="$dir/proj"
export YUKTA_CLAUDE_BIN="$dir/claude" YUKTA_CHECK_EXTRAS=off YUKTA_HEAL_EVENTS=off
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
# ---- the triage and the monitoring log, end to end: a stand-in for Ollama answers the difficulty the failure output names
python3 -I "$here/fake-ollama.py" "$dir/ollama.port" &
ollama_pid=$!
trap 'kill "$ollama_pid" 2>/dev/null || true; rm -rf "$dir"' EXIT
for _ in $(seq 1 50); do [[ -s "$dir/ollama.port" ]] && break; sleep 0.1; done
export YUKTA_TRIAGE_URL="http://127.0.0.1:$(cat "$dir/ollama.port")"
export YUKTA_HEAL_EVENTS="$dir/events.jsonl"
unset YUKTA_TRIAGE
triage_scenario() { # label difficulty fix-at expected-first-rung expected-route
  local label="$1" diff="$2" at="$3" first="$4" route="$5" result rc=0
  rm -f "$proj"/fail-* "$proj"/difficulty "$proj/.fake-fix-at" "$proj/fixed.txt" "$dir/claude.log" "$dir/events.jsonl"
  touch "$proj/fail-compileJava"; echo "$diff" >"$proj/difficulty"; echo "$at" >"$proj/.fake-fix-at"
  result="$("$yk" check -p "$proj" 2>&1)" || rc=$?
  sleep 6   # the watcher of the last run holds the lock for a moment
  [[ "$(head -1 "$dir/claude.log" | sed -E 's/.*model=([a-z]+) effort=([a-z]+).*/\1\/\2/')" == "$first" ]] || { tail -12 <<<"$result" >&2; fail "triage $label: the first fixer was $(head -1 "$dir/claude.log"), want $first"; }
  jq -e -s --arg route "$route" --arg first "$first" 'map(select(.event == "triage")) | length == 1 and .[0].route == $route and .[0].start != null' "$dir/events.jsonl" >/dev/null \
    || fail "triage $label: no triage event of route $route: $(grep triage "$dir/events.jsonl")"
  echo "ok   triage $label: first fixer $first, route $route"
}
triage_scenario "a hard failure starts at sonnet/high" hard sonnet/high sonnet/high level
jq -e -s 'map(select(.event == "triage"))[0] | .label == "hard" and .p == 0.7 and .start == "sonnet/high" and .fallback == null and .secs > 0 and .chars > 0' "$dir/events.jsonl" >/dev/null || fail "the triage event: $(grep triage "$dir/events.jsonl")"
jq -e -s '. as $e | ([$e[].event] | contains(["stage", "brief", "triage", "fix", "recheck", "result"])) and ([$e[] | select(.event == "result")][0] | .status == "SUCCESS" and .healed == "compile:sonnet/high" and .secs >= 0 and (.durations | has("compile")))' "$dir/events.jsonl" >/dev/null \
  || fail "the events of a healed run: $(jq -c .event "$dir/events.jsonl" | paste -sd' ')"
jq -e -s '[.[] | select(.event == "stage" or .event == "fix" or .event == "triage" or .event == "recheck")] | map(.run) | unique | length == 1' "$dir/events.jsonl" >/dev/null || fail "the events of one run do not share a run id"
triage_scenario "an uncertain answer falls back to sonnet/medium" uncertain sonnet/medium sonnet/medium uncertain
triage_scenario "a failed call falls back to sonnet/medium and says why" boom sonnet/medium sonnet/medium error
jq -e -s 'map(select(.event == "triage"))[0] | .fallback == "error" and .error != null' "$dir/events.jsonl" >/dev/null || fail "the error is not recorded: $(grep triage "$dir/events.jsonl")"
# the report and the findings read what the runs wrote
out="$("$yk" heal report)" || fail "yk heal report"
grep -q 'Triage: 1 call' <<<"$out" && grep -q 'compile' <<<"$out" || fail "report: $out"
"$yk" heal analyze | grep -q 'triage-fallbacks\|no findings\|giveups' || fail "analyze"
"$yk" heal show | grep -q '"event": "result"' || fail "show"
unset YUKTA_TRIAGE_URL YUKTA_HEAL_EVENTS
echo "ok   triage and monitoring"
echo "ok   online"
