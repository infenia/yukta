#!/usr/bin/env bash
# Where arm B's sub-flow money went: the implementer, fixer attempts that touched the issue's own module
# (MODULE in issues/<n>/grade.env) and fixer attempts that did not (failures elsewhere in the repo, or no
# fix made). One row per B run of EVAL_DIR.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$here" rev-parse --show-toplevel)"
EVAL_ISSUE="${EVAL_ISSUE:-1207}"
EVAL_DIR="${EVAL_DIR:-${YUKTA_DEV_STATE_DIR:-$repo_root/.yukta-dev}/eval}"
# shellcheck source=/dev/null
source "$here/issues/$EVAL_ISSUE/grade.env"
printf '%-4s %9s %9s %9s %9s %s\n' run implement "fix-in-$MODULE" fix-other total 'other attempts (stage#fixed[files changed])'
for d in "$EVAL_DIR"/runs/B*; do
  [[ -d "$d" ]] || continue
  run="$(basename "$d")"
  impl="$(jq -s 'map(.cost // 0) | add // 0' "$d"/yukta/.yukta-dev/issue-*/implement.json 2>/dev/null || echo 0)"
  heal=("$d"/yukta-issue-*/.yukta-dev/heal/check/*-[0-9]*.json)
  [[ -e "${heal[0]}" ]] || heal=()
  if ((${#heal[@]})); then
    read -r incli other detail < <(jq -rs --arg m "$MODULE/" '
      map(. + {own: ((.files // []) | any(startswith($m)))}) as $a
      | [($a | map(select(.own) | .cost // 0) | add // 0), ($a | map(select(.own | not) | .cost // 0) | add // 0),
         ($a | map(select(.own | not) | "\(.stage)#\(.fixed)[\((.files // []) | length)]") | join(",") | if . == "" then "-" else . end)] | @tsv' "${heal[@]}")
  else incli=0 other=0 detail=-; fi
  total="$(jq -r '.total.cost' "$d/result.json" 2>/dev/null || echo 0)"
  printf '%-4s %9.2f %9.2f %9.2f %9.2f %s\n' "$run" "${impl:-0}" "$incli" "$other" "$total" "$detail"
done
