#!/usr/bin/env bash
# Offline check of the stage workflows (no server): renders the gradle-check workflow, unscoped and
# scoped to a module, and asserts that every stage becomes a DAG whose edges name existing nodes, that
# routed edges leave routed nodes, that each exit is an OUTCOME_TERMINAL, that a RETRY restarts from
# "run", and that the stages follow the policy (auto -> RETRY exit, claude only -> ESCALATE, none -> FAILED).
set -euo pipefail
here="$(dirname "${BASH_SOURCE[0]}")"
source "$here/_lib.sh"
need jq
policy="$(cat "$here/../heal.json")"

check() { # scope-label module
  local out
  out="$(render_session "$here/../session.json" /p \
    | if [[ -n "$2" ]]; then scope_to_module "$2"; else cat; fi \
    | stage_workflows "$policy" "$2" /p /p/heal.sh /p/.yukta-dev/heal/s)"
  jq -e --argjson policy "$policy" '
    [.workflows | to_entries[] | select(.key | startswith("stage-"))] as $stages
    | ($stages | length > 0)
    and ($stages | all(.value as $w | .key as $k | ($k | sub("^stage-"; "")) as $s
        | ($w.nodes | map({(.nodeId): .}) | add) as $n
        | ($w.edges | all($n[.source] != null and $n[.target] != null))
        and ($w.edges | all(.sourcePort == null or $n[.source].config.routeByExitCode == true))
        and ($w.nodes | map(select(.type == "OUTCOME_TERMINAL")) | length > 1 or ($policy[$s] == null))
        and ([$w.nodes[] | select(.type == "OUTCOME_TERMINAL") | .config.outcome] as $o
             | if $policy[$s].auto then (["SUCCESS", "RETRY", "ESCALATE"] - $o | length == 0)
               elif $policy[$s].claude then (["SUCCESS", "ESCALATE"] - $o | length == 0)
               else $o == ["SUCCESS", "FAILED"] end)
        and ([$w.nodes[] | select(.config.outcome == "RETRY") | .config.restartFrom] | all(. == "run"))
        and ($n.run.config.failureMode == "CONTINUE")))
    | if . then true else error("stage graph check failed") end' <<<"$out" >/dev/null
  # every stage is a DAG: a topological order must exist (Kahn)
  jq -e '
    def kahn: . as $g | ($g.nodes | map(.nodeId)) as $ids
      | reduce range(0; $ids | length) as $_ ({left: $ids, edges: $g.edges};
          (.edges | map(.target)) as $t
          | .left as $l | ([ $l[] | select(. as $x | $t | index($x) | not) ]) as $free
          | .left -= $free | .edges |= map(select(.source as $s | $free | index($s) | not)))
      | .left | length == 0;
    [.workflows | to_entries[] | select(.key | startswith("stage-")) | .value | kahn] | all' <<<"$out" >/dev/null \
    || { echo "FAIL $1: cycle" >&2; exit 1; }
  echo "ok   $1: $(jq '[.workflows | keys[] | select(startswith("stage-"))] | length' <<<"$out") stage workflows"
}

check all ""
check core core
# stage order comes from gradle-check, and licenses (no policy) is a single fail-fast exit
render_session "$here/../session.json" /p | stage_workflows "$policy" "" /p /p/heal.sh /p/h \
  | jq -e '.workflows["stage-licenses"].nodes | map(.config.outcome) | index("FAILED") != null' >/dev/null \
  && echo "ok   licenses fails fast"
render_session "$here/../session.json" /p | stage_names | head -2 | paste -sd' ' | grep -qx 'spotless compile' \
  && echo "ok   stage order"
