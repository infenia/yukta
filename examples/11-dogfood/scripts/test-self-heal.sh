#!/usr/bin/env bash
# Offline check of the self-heal graph (no server): renders the gradle-check workflow, unscoped and
# scoped to a module, and asserts it is a DAG, that every edge leaving a routed node names a port,
# that every edge target exists, and that stages without a heal policy are left alone.
set -euo pipefail
here="$(dirname "${BASH_SOURCE[0]}")"
source "$here/_lib.sh"
need jq
policy="$(cat "$here/../heal.json")"

check() { # scope-label module
  local out
  out="$(render_session "$here/../session.json" /p \
    | if [[ -n "$2" ]]; then scope_to_module "$2"; else cat; fi \
    | self_heal "$policy" "$2" /p /p/heal.sh /p/.yukta-dev/heal/s FORK fresh fork \
    | jq '.workflows["gradle-check"]')"
  jq -e --argjson policy "$policy" '
    (.nodes | map({(.nodeId): .}) | add) as $n
    | (.edges | all(.source as $s | .target as $t | $n[$s] != null and $n[$t] != null)) as $ends
    | (.edges | all(.source as $s | ($n[$s].config.routeByExitCode // false) == false or (.sourcePort != null))) as $ports
    | (.edges | map(select(.sourcePort != null) | .source) | all(. as $s | $n[$s].config.routeByExitCode == true)) as $routed
    | ([.nodes[] | select(.nodeId | test("-(auto|fix[0-9]|r[0-9])$")) | .nodeId | sub("-.*$"; "")] | unique) as $healed
    | ($healed | all(. as $s | $policy[$s] != null)) as $policyOnly
    | $ends and $ports and $routed and $policyOnly
    | if . then true else error("graph check failed") end' <<<"$out" >/dev/null
  # DAG: a topological order must exist (Kahn)
  jq -e '
    def kahn: . as $g | ($g.nodes | map(.nodeId)) as $ids
      | reduce range(0; $ids | length) as $_ ({left: $ids, edges: $g.edges};
          (.edges | map(.target)) as $t
          | .left as $l | ([ $l[] | select(. as $x | $t | index($x) | not) ]) as $free
          | .left -= $free | .edges |= map(select(.source as $s | $free | index($s) | not)))
      | .left | length == 0;
    kahn' <<<"$out" >/dev/null || { echo "FAIL $1: cycle" >&2; exit 1; }
  echo "ok   $1: $(jq '.nodes | length' <<<"$out") nodes, $(jq '.edges | length' <<<"$out") edges"
}

check all ""
check core core
# a stage with no policy entry (licenses) must stay a single plain node
render_session "$here/../session.json" /p | self_heal "$policy" "" /p /p/heal.sh /p/h "" fresh fresh \
  | jq -e '[.workflows["gradle-check"].nodes[].nodeId | select(startswith("licenses"))] == ["licenses"]' >/dev/null \
  && echo "ok   licenses untouched"
