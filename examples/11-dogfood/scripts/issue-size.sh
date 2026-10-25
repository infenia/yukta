#!/usr/bin/env bash
# Size of an issue for the loop's balanced draw: a deterministic score from the issue text, bucketed into S, M, L.
#
#   issue-size.sh <n>     reads the issue with gh (YUKTA_GH, YUKTA_ISSUES_REPO)
#   issue-size.sh -       reads {"body": ..., "labels": [{"name": ...}]} on stdin
#
# score = 2 x acceptance criteria + 3 x modules named + words / 150. A label size:S, size:M or size:L overrides it.
# Output: {"size":"S|M|L","score":N,"source":"label|heuristic","criteria":N,"modules":N,"module_names":[...],"words":N}
# Thresholds (YUKTA_SIZE_M, YUKTA_SIZE_L; default 6 and 9) were set on the 41 open issues of epic #1218 (2026-10-25),
# which fall 15 S, 15 M, 10 L.
set -euo pipefail
src="${1:?usage: issue-size.sh <n> | -}"
if [[ "$src" == - ]]; then
  json="$(cat)"
else
  json="$("${YUKTA_GH:-gh}" issue view "$src" -R "${YUKTA_ISSUES_REPO:-infenia/yukta}" --json body,labels)"
fi
jq -c --argjson m "${YUKTA_SIZE_M:-6}" --argjson l "${YUKTA_SIZE_L:-9}" '
  (.body // "") as $b
  | ($b | split("\n")) as $lines
  | (reduce $lines[] as $x ({in: false, n: 0};
      if ($x | test("^#+\\s*accept"; "i")) then .in = true
      elif ($x | test("^#+\\s")) then .in = false
      elif ($x | test("^\\s*[-*]\\s+\\[[ xX]\\]")) then .n += 1
      elif (.in and ($x | test("^\\s*([-*]|[0-9]+\\.)\\s+\\S"))) then .n += 1
      else . end) | .n) as $criteria
  | ($b | ascii_downcase) as $low
  | (["boot","build-logic","cli","core","mcp","messaging","plugin-api","plugins","persistence-postgres","workers","ui","web"]
     | map(. as $mod | select($low | test("(^|[^a-z-])" + $mod + "(/|[^a-z-]|$)")))) as $names
  | ($names | length) as $modules
  | ([$b | match("\\S+"; "g")] | length) as $words
  | (2 * $criteria + 3 * $modules + ($words / 150 | floor)) as $score
  | ([.labels[]?.name | select(test("^size:[SML]$")) | .[5:]] | first) as $label
  | {size: ($label // (if $score >= $l then "L" elif $score >= $m then "M" else "S" end)), score: $score,
     source: (if $label then "label" else "heuristic" end), criteria: $criteria, modules: $modules, module_names: $names, words: $words}
' <<<"$json"
