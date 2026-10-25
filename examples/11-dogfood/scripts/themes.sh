#!/usr/bin/env bash
# What keeps going wrong in the loop, counted: the analyzer's findings and notes across the runs, by theme.
#
#   themes.sh [--loop SHA] [--no-judge] [--post]
#
# 1. Rules (no model): for each analyzer rule, how many analyzed runs it fired in and what those runs cost, with the open issues
#    that describe it (analyzer-known.json).
# 2. Themes (one cheap judge call, YUKTA_ANALYZER_MODEL sonnet, YUKTA_ANALYZER_BUDGET 0.30 USD, themes-rules.md): the free-text
#    notes of the analyses grouped into at most six causes (axial coding after the open coding the analyzer does per run). The model
#    only groups run ids; the counts and the dollars are computed here from the analyses. Ranked by runs x dollars.
# --post comments the counts on the open epic issue a theme matches (once per change, kept in $LOOP_DIR/themes/posted.json).
# --no-judge stops after the rules. --loop SHA limits it to the runs of one loop commit. The judge's cost goes on the ledger and
# counts in the budget. Output goes to the terminal and to $LOOP_DIR/themes/<time>.json.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/_lib.sh"
LOOP_DIR="${YUKTA_LOOP_DIR:-$DEV_HOME/loop}"
gh="${YUKTA_GH:-gh}"
gh_repo="${YUKTA_ISSUES_REPO:-infenia/yukta}"
epic="${YUKTA_LOOP_EPIC:-1218}"
need jq
judge=1 post=0 loop=""
while (($#)); do
  case "$1" in
    --no-judge) judge=0; shift ;;
    --post) post=1; shift ;;
    --loop) loop="${2:?--loop needs a loop commit}"; shift 2 ;;
    *) die "unknown option '$1'" ;;
  esac
done
compgen -G "$LOOP_DIR/analysis/*.json" >/dev/null || die "no analyses in $LOOP_DIR/analysis yet (yk analyze, or yk pick runs it)"
analyses="$(jq -cs --arg l "$loop" '[.[] | select($l == "" or (.facts.loop_sha // "" | startswith($l)))]' "$LOOP_DIR"/analysis/*.json)"
runs="$(jq length <<<"$analyses")"
((runs > 0)) || die "no analysis of loop commit $loop"
known="$(cat "$here/../analyzer-known.json")"
echo "# Themes of $runs analyzed run(s)${loop:+ (loop $loop)}"

# ---- 1. the rules
echo
echo "## Rules (no model)"
echo
echo "| rule | runs | cost of those runs | issues |"
echo "|---|---|---|---|"
jq -r --argjson k "$known" '
  [.[] | . as $a | .findings[] | {rule, run: $a.facts.run, cost: ($a.facts.cost_usd // 0)}]
  | group_by(.rule) | map({rule: .[0].rule, runs: length, cost: (map(.cost) | add)}) | sort_by(-.runs, -.cost)[]
  | "| \(.rule) | \(.runs) of '"$runs"' | $\(.cost * 100 | round / 100) | \(($k[.rule].issues // []) | map("#\(.)") | join(", ") | if . == "" then "-" else . end) |"' <<<"$analyses"
[[ "$(jq '[.[] | .findings[]] | length' <<<"$analyses")" != 0 ]] || echo "| (no rule fired) | 0 | - | - |"

# ---- 2. the themes
themes='[]' cost=0
notes="$(jq -c '[.[] | select((.note // "") != "") | {run: .facts.run, method: .facts.method, size: .facts.size, cost_usd: (.facts.cost_usd // 0), rules: [.findings[].rule], note: .note}]' <<<"$analyses")"
echo
echo "## Themes (from the notes)"
echo
if ((judge == 0)); then
  echo "Skipped (--no-judge)."
elif (($(jq length <<<"$notes") < 3)); then
  echo "Not enough notes yet: $(jq length <<<"$notes") (at least 3 are needed to find a theme)."
else
  source "$here/_claude.sh"
  titles="$("$gh" api "repos/$gh_repo/issues/$epic/sub_issues" --paginate --jq '.[] | select(.state == "open") | "#\(.number) \(.title)"' 2>/dev/null || true)"
  YK_SCHEMA='{"type":"object","properties":{"themes":{"type":"array","maxItems":6,"items":{"type":"object","properties":{"name":{"type":"string"},"description":{"type":"string"},"runs":{"type":"array","items":{"type":"string"}},"issue":{"type":["integer","null"]}},"required":["name","description","runs","issue"]}}},"required":["themes"]}'
  mkdir -p "$LOOP_DIR/themes"
  result="$(yk_claude "${YUKTA_ANALYZER_MODEL:-sonnet}" "Notes of the analyzed runs (JSON):
$notes

Open issues of the dogfood epic:
${titles:-none}" "$here/../themes-rules.md" "${YUKTA_ANALYZER_BUDGET:-0.30}" "$LOOP_DIR/themes/judge.err" 2>/dev/null || true)"
  cost="$(jq -r '.total_cost_usd // 0' <<<"${result:-null}" 2>/dev/null || echo 0)"
  raw="$(jq -c '(.structured_output // (.result | fromjson? ) // {}) | .themes // []' <<<"${result:-null}" 2>/dev/null || echo '[]')"
  # the counts and the dollars come from the analyses, not from the model; an invented run id counts for nothing
  themes="$(jq -c --argjson a "$analyses" '
    ($a | map({key: .facts.run, value: (.facts.cost_usd // 0)}) | from_entries) as $cost
    | map(. + {runs: (.runs | map(select($cost[.] != null)) | unique)}) | map(select(.runs | length > 0))
    | map(. + {count: (.runs | length), cost_usd: ((.runs | map($cost[.]) | add) * 100 | round / 100)})
    | sort_by(-(.count * .cost_usd))' <<<"$raw")"
  if [[ "$(jq length <<<"$themes")" == 0 ]]; then echo "The judge found no recurring theme."; else
    echo "| theme | runs | cost of those runs | issue |"
    echo "|---|---|---|---|"
    jq -r '.[] | "| \(.name): \(.description) | \(.count) | $\(.cost_usd) | \(if .issue then "#\(.issue)" else "-" end) |"' <<<"$themes"
  fi
fi
mkdir -p "$LOOP_DIR/themes"
jq -n --argjson themes "$themes" --argjson runs "$runs" --argjson cost "$cost" --arg t "$(date -u +%FT%TZ)" '{time: $t, analyzed_runs: $runs, judge_cost_usd: $cost, themes: $themes}' >"$LOOP_DIR/themes/$(date -u +%Y%m%dT%H%M%SZ).json"
"$here/loop.sh" record "$(jq -nc --argjson cost "$cost" --argjson n "$(jq length <<<"$themes")" --arg t "$(date -u +%FT%TZ)" '{type: "themes", time: $t, analyzer_cost_usd: $cost, themes: $n}')"
echo
echo "Judge cost \$$cost."

# ---- --post: the counts on the issue a theme matches, once per change
if ((post)); then
  posted="$LOOP_DIR/themes/posted.json"
  [[ -s "$posted" ]] || echo '{}' >"$posted"
  while IFS= read -r th; do
    [[ -n "$th" ]] || continue
    n="$(jq -r '.issue // empty' <<<"$th")"
    [[ -n "$n" ]] || continue
    [[ "$("$gh" issue view "$n" -R "$gh_repo" --json state -q .state 2>/dev/null)" == OPEN ]] || continue
    stamp="$(jq -r '"\(.count)|\(.cost_usd)"' <<<"$th")"
    [[ "$(jq -r --arg n "$n" '.[$n] // ""' "$posted")" != "$stamp" ]] || continue
    "$gh" issue comment "$n" -R "$gh_repo" --body "Theme counts from the improvement loop ($runs analyzed runs): **$(jq -r .name <<<"$th")** shows in $(jq -r .count <<<"$th") run(s) that cost \$$(jq -r .cost_usd <<<"$th") in total ($(jq -r '.runs | join(", ")' <<<"$th"))." >/dev/null \
      && { jq --arg n "$n" --arg s "$stamp" '.[$n] = $s' "$posted" >"$posted.tmp" && mv "$posted.tmp" "$posted"; echo "POSTED #$n"; }
  done < <(jq -c '.[]' <<<"$themes")
fi
