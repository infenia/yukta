#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# ticketkit decision NAME RUN_DIR [STEP [WORKTREE]]: one decision of the local model (design 3.2, 15.8), through the `decide` helper of 11-dogfood.
#   NAME: triage (triage_complexity and triage_risk, after route) | cause | merge_risk
# The static rule decides in shadow mode; the model's answer is only logged (decisions.tsv, `yk stats --decisions`) next to the static label as the
# outcome. In mode `on` (decisions.json "mode", or YUKTA_DECISION_<NAME>=on: a config change, nothing else) the model's label is used when it answered
# with enough confidence. No model, an error, an invalid answer or a digest over YUKTA_DECISION_MAX_INPUT: the static label, one WARN line.
# Prints one JSON line per decision {name, label, source, static}.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dog="$here/../11-dogfood"
source "$dog/scripts/_lib.sh"
cfg="$dog/decisions.json"
one() { # NAME RUN STEP WORKTREE
  local name="$1" run="$2" step="${3:--}" wt="${4:-}" in state static question criteria label source=static out min prob
  in="$(python3 "$here/scripts/decision.py" inputs "$name" "$run" "$step" ${wt:+"$wt"})"
  state="$(jq -r .state <<<"$in")" static="$(jq -r .static <<<"$in")"
  question="$(jq -r --arg n "$name" '.decisions[$n].question' "$cfg")" criteria="$(jq -c --arg n "$name" '.decisions[$n].criteria' "$cfg")"
  min="$(jq -r --arg n "$name" '.decisions[$n].minProbability // 0.6' "$cfg")"
  label="$static"
  # decide returns 0 only in mode on, with an answer; in shadow it logs the answer and returns 1
  if out="$(decide "$name" "$state" "$question" "$criteria")"; then
    prob="${out#* }"
    if awk -v p="$prob" -v m="$min" 'BEGIN { exit !(p >= m) }'; then label="${out% *}" source=model; fi
  fi
  decision_outcome "$name" "$state" "$static"  # what the static rule says is the reference the model is judged against
  python3 "$here/scripts/decision.py" apply "$name" "$run" "$label" "$step"
  jq -nc --arg n "$name" --arg l "$label" --arg s "$source" --arg st "$static" '{name: $n, label: $l, source: $s, static: $st}'
}
case "${1:-}" in
  triage) one triage_complexity "${2:?RUN}" - ; one triage_risk "$2" - ;;
  cause) one cause "${2:?RUN}" "${3:?STEP}" ;;
  merge_risk) one merge_risk "${2:?RUN}" - "${4:-}" ;;
  *) echo "usage: ticketkit decision triage|cause|merge_risk RUN_DIR [STEP [WORKTREE]]" >&2; exit 2 ;;
esac
