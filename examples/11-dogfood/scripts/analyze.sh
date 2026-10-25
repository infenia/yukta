#!/usr/bin/env bash
# The analyzer of the improvement loop: reads one finished run, says what was wasteful, and files it under the epic.
#
#   analyze.sh <draw-id> [--dry-run] [--no-judge]
#
# Stage 1 (no model): facts and rule findings from the ledger, the heal and implement records, the git diff of the
#   issue worktree and the session transcripts (scripts/analyze-facts.jq, eval/cache_trace.py --json).
# Stage 2 (one cheap model call, YUKTA_ANALYZER_MODEL sonnet, YUKTA_ANALYZER_BUDGET 0.30 USD): an open-code note about
#   the run and at most two proposals the rules and the open issues do not cover (analyzer-rules.md).
# Stage 3: a rule finding becomes a comment on the first still-open issue that describes it (analyzer-known.json), or a
#   new issue when all are closed (a rule marked "file": false is only noted); a proposal that duplicates an open issue becomes a comment, a new one is filed with
#   report-finding.sh (at most YUKTA_ANALYZER_MAX_FILED, 3, per run) and attached under the epic (YUKTA_LOOP_EPIC, 1218).
#   Nothing is implemented: you decide by marking an issue Ready.
# --dry-run prints what it would comment on and file and writes nothing to GitHub. --no-judge skips stage 2.
#
# The analysis is written to $LOOP_DIR/analysis/<draw-id>.json and an analysis record (with the judge's cost, which counts
# toward the loop's budget) is appended to the ledger. Environment: YUKTA_LOOP_DIR, YUKTA_GH, YUKTA_ISSUES_REPO,
# YUKTA_CLAUDE_BIN, YUKTA_REPO_ROOT as for loop.sh.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/_lib.sh"
LOOP_DIR="${YUKTA_LOOP_DIR:-$DEV_HOME/loop}"
LEDGER="$LOOP_DIR/ledger.jsonl"
gh="${YUKTA_GH:-gh}"
gh_repo="${YUKTA_ISSUES_REPO:-infenia/yukta}"
epic="${YUKTA_LOOP_EPIC:-1218}"
max_filed="${YUKTA_ANALYZER_MAX_FILED:-3}"
need jq
id="${1:?usage: analyze.sh <draw-id> [--dry-run] [--no-judge]}"
shift
dry=0 judge=1
while (($#)); do
  case "$1" in
    --dry-run) dry=1; shift ;;
    --no-judge) judge=0; shift ;;
    *) die "unknown option '$1'" ;;
  esac
done

draw="$(jq -s --arg id "$id" 'map(select(.type == "draw" and .id == $id)) | first // empty' "$LEDGER" 2>/dev/null)"
[[ -n "$draw" ]] || die "no draw $id in the ledger"
outcome="$(jq -s --arg id "$id" 'map(select(.type == "outcome" and .draw == $id)) | last // empty' "$LEDGER")"
[[ -n "$outcome" ]] || die "draw $id has no outcome yet"
issue="$(jq -r .issue <<<"$draw")"
wt="$(dirname "$REPO_ROOT")/yukta-issue-$issue"
state="$REPO_ROOT/.yukta-dev/issue-$issue"

# ---- stage 1: facts and findings
json_or() { jq -c . "$1" 2>/dev/null || echo "$2"; }
attempts='[]'
if compgen -G "$wt/.yukta-dev/heal/check/*-[0-9]*.json" >/dev/null; then attempts="$(jq -cs . "$wt"/.yukta-dev/heal/check/*-[0-9]*.json)"; fi
implement=null
for f in "$state/implement.json" "$state/solo.json"; do [[ -s "$f" ]] && implement="$(json_or "$f" null)"; done
issue_json="$("$gh" issue view "$issue" -R "$gh_repo" --json number,title,body,labels 2>/dev/null || echo '{}')"
modules="$("$here/issue-size.sh" - <<<"$issue_json" 2>/dev/null | jq -c '.module_names // []' || echo '[]')"
files='[]'
if [[ -d "$wt" ]]; then
  files="$({ git -C "$wt" diff --name-only origin/main...HEAD 2>/dev/null; git -C "$wt" status --porcelain 2>/dev/null | awk '{print $NF}'; } | { grep -v '^\.yukta-dev/' || true; } | sort -u | jq -R . | jq -sc .)"
fi
nav=null; [[ -s "$state/nav.json" ]] && nav="$(json_or "$state/nav.json" null)"
traces="$(python3 -I "$here/../eval/cache_trace.py" --worktree "$wt" --json 2>/dev/null || echo '{"sessions":[]}')"
stage1="$(jq -n --argjson draw "$draw" --argjson outcome "$outcome" --argjson attempts "$attempts" --argjson implement "$implement" \
  --argjson modules "$modules" --argjson files "$files" --argjson traces "$traces" --argjson nav "$nav" \
  '{draw: $draw, outcome: $outcome, attempts: $attempts, implement: $implement, modules: $modules, files: $files, traces: $traces, nav: $nav}' \
  | jq -c -f "$here/analyze-facts.jq")"

# ---- stage 2: the judge
note="" proposals='[]' judge_cost=0
epic_titles="$("$gh" api "repos/$gh_repo/issues/$epic/sub_issues" --paginate --jq '.[] | select(.state == "open") | "#\(.number) \(.title)"' 2>/dev/null || true)"
if ((judge)); then
  source "$here/_claude.sh"
  prompt="Run $id: issue #$issue ($(jq -r '.title // ""' <<<"$issue_json")), method $(jq -r .method <<<"$draw"), size $(jq -r .size <<<"$draw"), loop $(jq -r .loop_sha <<<"$draw").

Facts (JSON):
$(jq -c .facts <<<"$stage1")

Findings the rules already detected (do not repeat them): $(jq -c '[.findings[].rule]' <<<"$stage1")

Open issues of the dogfood epic (set duplicate_of for a proposal that matches one):
${epic_titles:-none}"
  YK_SCHEMA='{"type":"object","properties":{"note":{"type":"string"},"proposals":{"type":"array","maxItems":2,"items":{"type":"object","properties":{"title":{"type":"string"},"evidence":{"type":"string"},"expected":{"type":"string"},"priority":{"type":"string","enum":["P1","P2","P3"]},"module":{"type":"string"},"kind":{"type":"string","enum":["bug","perf","improvement","ux"]},"scope":{"type":"string","enum":["dogfood","yukta"]},"duplicate_of":{"type":["integer","null"]}},"required":["title","evidence","expected","priority","module","kind","scope","duplicate_of"]}}},"required":["note","proposals"]}'
  mkdir -p "$LOOP_DIR/analysis"
  result="$(yk_claude "${YUKTA_ANALYZER_MODEL:-sonnet}" "$prompt" "$here/../analyzer-rules.md" "${YUKTA_ANALYZER_BUDGET:-0.30}" "$LOOP_DIR/analysis/$id.err" 2>/dev/null || true)"
  answer="$(jq -c '.structured_output // (.result | fromjson? ) // {}' <<<"${result:-null}" 2>/dev/null || echo '{}')"
  judge_cost="$(jq -r '.total_cost_usd // 0' <<<"${result:-null}" 2>/dev/null || echo 0)"
  note="$(jq -r '.note // ""' <<<"$answer")"
  proposals="$(jq -c '.proposals // []' <<<"$answer")"
fi

# ---- stage 3: comment on what is known, file what is new
known="$(cat "$here/../analyzer-known.json")"
filed='[]' new_filed=0
tag="analyzer, run $id (issue #$issue, method $(jq -r .method <<<"$draw"), loop $(jq -r .loop_sha <<<"$draw"))"
say() { printf '%s\n' "$*"; }

comment_on() { # NUMBER TEXT
  if ((dry)); then say "WOULD COMMENT #$1: ${2:0:100}"; else
    "$gh" issue comment "$1" -R "$gh_repo" --body "Seen again by the $tag:

$2" >/dev/null && say "COMMENTED #$1"
  fi
  filed="$(jq -c --argjson n "$1" '. + [{action: "comment", issue: $n}]' <<<"$filed")"
}

file_issue() { # TITLE MODULE PRIORITY KIND EVIDENCE EXPECTED
  if ((new_filed >= max_filed)); then say "SKIPPED (at most $max_filed new issues per run): $1"; return; fi
  new_filed=$((new_filed + 1))
  if ((dry)); then
    say "WOULD FILE: $1 [$3 $4 $2]"
    filed="$(jq -c --arg t "$1" '. + [{action: "file", title: $t, dry: true}]' <<<"$filed")"
    return
  fi
  local out url n
  out="$("$here/report-finding.sh" --title "$1" --module "$2" --priority "$3" --kind "$4" --evidence "$5 ($tag)" --expected "$6" --repro "See run $id of the improvement loop (yk loop ledger)." 2>&1 | tail -1)"
  say "$out"
  url="${out##* }"
  n="${url##*/}"
  if [[ "$out" == FILED* && "$n" =~ ^[0-9]+$ ]]; then
    "$gh" api -X POST "repos/$gh_repo/issues/$epic/sub_issues" -F sub_issue_id="$("$gh" api "repos/$gh_repo/issues/$n" --jq .id)" >/dev/null 2>&1 \
      && say "ATTACHED #$n to #$epic" || say "could not attach #$n to #$epic"
  fi
  filed="$(jq -c --arg t "$1" --arg u "$url" '. + [{action: "file", title: $t, url: $u}]' <<<"$filed")"
}

# a rule finding: comment on the first open issue that describes it, else file the rule's own issue again
while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  rule="$(jq -r .rule <<<"$f")" evidence="$(jq -r .evidence <<<"$f")" target=""
  if [[ "$(jq -r --arg r "$rule" '.[$r] | if has("file") then .file else true end' <<<"$known")" == false ]]; then say "NOTED $rule: ${evidence:0:160}"; continue; fi
  for n in $(jq -r --arg r "$rule" '.[$r].issues[]' <<<"$known"); do
    if [[ "$("$gh" issue view "$n" -R "$gh_repo" --json state -q .state 2>/dev/null)" == OPEN ]]; then target="$n"; break; fi
  done
  if [[ -n "$target" ]]; then comment_on "$target" "$rule: $evidence"
  else
    file_issue "$(jq -r --arg r "$rule" '.[$r].title' <<<"$known")" "$(jq -r --arg r "$rule" '.[$r].module' <<<"$known")" \
      "$(jq -r --arg r "$rule" '.[$r].priority' <<<"$known")" "$(jq -r --arg r "$rule" '.[$r].kind' <<<"$known")" \
      "$evidence The earlier issue(s) $(jq -r --arg r "$rule" '.[$r].issues | map("#\(.)") | join(", ")' <<<"$known") are closed." "the rule $rule no longer fires"
  fi
done < <(jq -c '.findings[]' <<<"$stage1")

# the judge's proposals
modules_ok=" boot build cli core mcp messaging plugin-api plugins persistence-postgres workers ui web documentation "
while IFS= read -r p; do
  [[ -n "$p" ]] || continue
  dup="$(jq -r '.duplicate_of // empty' <<<"$p")"
  if [[ -n "$dup" ]] && grep -q "^#$dup " <<<"$epic_titles"; then
    comment_on "$dup" "$(jq -r '.evidence' <<<"$p")"
    continue
  fi
  m="$(jq -r .module <<<"$p")"; [[ "$modules_ok" == *" $m "* ]] || m=build
  file_issue "$(jq -r .title <<<"$p")" "$m" "$(jq -r .priority <<<"$p")" "$(jq -r .kind <<<"$p")" \
    "$(jq -r '"Proposed by the analyzer (the fix belongs to: \(.scope)). \(.evidence)"' <<<"$p")" "$(jq -r .expected <<<"$p")"
done < <(jq -c '.[]' <<<"$proposals")

# ---- keep it
mkdir -p "$LOOP_DIR/analysis"
jq -n --argjson s "$stage1" --arg note "$note" --argjson proposals "$proposals" --argjson filed "$filed" --argjson cost "$judge_cost" \
  --argjson dry "$dry" --arg t "$(date -u +%FT%TZ)" \
  '$s + {note: $note, proposals: $proposals, filed: $filed, analyzer_cost_usd: $cost, dry_run: ($dry == 1), time: $t}' >"$LOOP_DIR/analysis/$id.json"
"$here/loop.sh" record "$(jq -nc --arg id "$id" --argjson n "$issue" --argjson cost "$judge_cost" --argjson findings "$(jq '.findings | length' <<<"$stage1")" \
  --argjson proposals "$(jq length <<<"$proposals")" --argjson filed "$filed" --argjson dry "$dry" --arg t "$(date -u +%FT%TZ)" \
  '{type: "analysis", draw: $id, time: $t, issue: $n, analyzer_cost_usd: $cost, findings: $findings, proposals: $proposals, filed: $filed, dry_run: ($dry == 1)}')"
say "ANALYSIS $id: $(jq '.findings | length' <<<"$stage1") finding(s), $(jq length <<<"$proposals") proposal(s), judge \$$judge_cost; ${note:+note: ${note:0:200}}"
