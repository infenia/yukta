#!/usr/bin/env bash
# Offline test of the improvement loop (loop.sh, issue-size.sh): size buckets, balanced seeded draws, the ledger,
# the budget gate and a whole pick with a stand-in runner. No server, no Claude, no network.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
mkdir -p "$dir/issues" "$dir/repo/.yukta-dev"
export YUKTA_LOOP_DIR="$dir/loop" YUKTA_REPO_ROOT="$dir/repo" YUKTA_GH="$dir/gh" YUKTA_LOOP_SEED=seed-one YUKTA_LOOP_METHODS=A,B,C
export YUKTA_LOOP_ANALYZE=off  # pick would otherwise run the analyzer, whose judge call spends money (test-analyze.sh covers it)
loop="$here/loop.sh"

# fake gh: issue JSON from $dir/issues/<n>.json, you are "tester", every issue is Ready except those listed in $dir/notready
cat >"$dir/gh" <<'SH'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")" && pwd)"
case "$1 $2" in
  "api user") echo tester ;;
  "issue view") cat "$here/issues/$3.json" ;;
  "issue list") jq -s '[.[] | select(.assignees[0].login == "tester")]' "$here"/issues/*.json ;;
  "project item-list")
    if grep -q 'status==' <<<"$*"; then  # the Ready list with priorities, as the next command asks for it
      for f in "$here"/issues/*.json; do
        n="$(basename "$f" .json)"
        grep -qx "$n" "$here/notready" 2>/dev/null || printf '%s\t%s\n' "$n" "$(cat "$here/prio/$n" 2>/dev/null || echo P2)"
      done
    else
      n="$(grep -o 'number == [0-9]*' <<<"$*" | grep -o '[0-9]*$')"
      if grep -qx "$n" "$here/notready" 2>/dev/null; then echo Backlog; else echo Ready; fi
    fi ;;
  *) echo "fake gh: unsupported: $*" >&2; exit 1 ;;
esac
SH
chmod +x "$dir/gh"

issue() { # N BODY [LABELS-JSON] [ASSIGNEE]; writes to $idir (default $dir/issues)
  jq -nc --argjson n "$1" --arg b "$2" --argjson l "${3:-[]}" --arg a "${4:-tester}" \
    '{number: $n, title: ("Issue " + ($n | tostring)), body: $b, labels: $l, state: "OPEN", assignees: [{login: $a}]}' >"${idir:-$dir/issues}/$1.json"
}
small="A short problem."
medium=$'## Acceptance\n- one\n- two\n- three\nText.'
large=$'## Acceptance\n- one\n- two\n- three\nTouches `core/` and `plugins/` and cli.'

# --- size
size() { jq -r .size <<<"$(printf '%s' "$1" | jq -Rs --argjson l "${2:-[]}" '{body: ., labels: $l}' | "$here/issue-size.sh" -)"; }
[[ "$(size "$small")" == S ]] || fail "small is $(size "$small")"
[[ "$(size "$medium")" == M ]] || fail "medium is $(size "$medium")"
[[ "$(size "$large")" == L ]] || fail "large is $(size "$large")"
[[ "$(size "$small" '[{"name":"size:L"}]')" == L ]] || fail "label override"
[[ "$(size "- [x] done
- [ ] todo
- [ ] more
- [ ] again")" == M ]] || fail "checkboxes count as criteria"

# --- balanced draws: 30 small issues, three methods
for n in $(seq 1 30); do issue "$n" "$small"; done
for n in $(seq 1 30); do "$loop" plan "$n" >/dev/null; done
seq_of() { jq -r 'select(.type == "draw" and .size == "S") | .method' "$YUKTA_LOOP_DIR/ledger.jsonl" | paste -sd' '; }
first="$(seq_of)"
for m in A B C; do
  c="$(tr ' ' '\n' <<<"$first" | grep -c "^$m$")"
  [[ "$c" == 10 ]] || fail "method $m was drawn $c times of 30 (want 10): $first"
done
for b in $(seq 0 9); do  # every block of three holds each method once
  blk="$(tr ' ' '\n' <<<"$first" | sed -n "$((b * 3 + 1)),$((b * 3 + 3))p" | sort | paste -sd' ')"
  [[ "$blk" == "A B C" ]] || fail "block $b is '$blk'"
done
[[ "$(jq -s '[.[] | select(.type == "draw")] | map(.block) | max' "$YUKTA_LOOP_DIR/ledger.jsonl")" == 9 ]] || fail "block numbers"

# same seed, same draws; another seed, other draws
second="$(YUKTA_LOOP_DIR="$dir/loop2" bash -c 'for n in $(seq 1 30); do "'"$loop"'" plan "$n" >/dev/null; done; jq -r "select(.type == \"draw\" and .size == \"S\") | .method" "'"$dir"'/loop2/ledger.jsonl" | paste -sd" "')"
[[ "$second" == "$first" ]] || fail "same seed gave another order: $second"
third="$(YUKTA_LOOP_SEED=seed-two YUKTA_LOOP_DIR="$dir/loop3" bash -c 'for n in $(seq 1 30); do "'"$loop"'" plan "$n" >/dev/null; done; jq -r "select(.type == \"draw\" and .size == \"S\") | .method" "'"$dir"'/loop3/ledger.jsonl" | paste -sd" "')"
[[ "$third" != "$first" ]] || fail "another seed gave the same order"

# strata have their own blocks
for n in 101 102 103; do issue "$n" "$medium"; done
for n in 101 102 103; do "$loop" plan "$n" >/dev/null; done
[[ "$(jq -r 'select(.type == "draw" and .size == "M") | .method' "$YUKTA_LOOP_DIR/ledger.jsonl" | sort | paste -sd' ')" == "A B C" ]] || fail "the first block of M is not A B C"

# a second plan of the same issue keeps its method and writes nothing; --dry-run writes nothing; --method is forced and not counted
lines="$(wc -l <"$YUKTA_LOOP_DIR/ledger.jsonl")"
again="$("$loop" plan 1)"
[[ "$(jq -r .reused <<<"$again")" == true && "$(jq -r .method <<<"$again")" == "$(cut -d' ' -f1 <<<"$first")" ]] || fail "issue 1 was drawn again: $again"
issue 200 "$small"
"$loop" plan 200 --dry-run >/dev/null
[[ "$(wc -l <"$YUKTA_LOOP_DIR/ledger.jsonl")" == "$lines" ]] || fail "reuse or --dry-run wrote to the ledger"
forced="$("$loop" plan 200 --method C)"
[[ "$(jq -r .forced <<<"$forced")" == true && "$(jq -r .method <<<"$forced")" == C ]] || fail "forced draw: $forced"
issue 201 "$small"
next="$("$loop" plan 201 --dry-run | jq -r .method)"
[[ "$next" == "$(YUKTA_LOOP_DIR="$dir/loop2" "$loop" plan 201 --dry-run | jq -r .method)" ]] || fail "a forced draw moved the blocks"

# loop-changing flag
issue 300 "Change examples/11-dogfood/yk so it prints more."
issue 301 "Fix the web controller." '[{"name":"loop-changing"}]'
issue 302 "Change examples/11-dogfood/yk." '[{"name":"loop-neutral"}]'
[[ "$("$loop" plan 300 --dry-run | jq -r .loop_changing)" == true ]] || fail "dogfood path not flagged"
[[ "$("$loop" plan 301 --dry-run | jq -r .loop_changing)" == true ]] || fail "label loop-changing"
[[ "$("$loop" plan 302 --dry-run | jq -r .loop_changing)" == false ]] || fail "label loop-neutral"
[[ "$("$loop" plan 201 --dry-run | jq -r .loop_changing)" == false ]] || fail "plain issue flagged"
[[ -n "$("$loop" plan 201 --dry-run | jq -r .loop_sha)" ]] || fail "no loop sha"

# default pool: A and B
issue 600 "$small"; issue 601 "$small"
default="$(env -u YUKTA_LOOP_METHODS bash -c 'for n in 600 601; do "'"$loop"'" plan "$n" --dry-run | jq -r .regime; done' | sort -u)"
[[ "$default" == "A,B" ]] || fail "default pool is '$default', not A,B"
d="$(env -u YUKTA_LOOP_DIR -u YUKTA_LOOP_METHODS YUKTA_LOOP_DIR="$dir/loop5" bash -c '"'"$loop"'" plan 600 | jq -r .method; "'"$loop"'" plan 601 | jq -r .method' | sort | paste -sd' ')"
[[ "$d" == "A B" ]] || fail "the first block of the default pool is '$d', not A B"

# --- pick with a stand-in runner: the draw is in the ledger before the run, the outcome after it
export YUKTA_LOOP_DIR="$dir/loop4" YUKTA_LOOP_METHODS=B
issue 400 "$small"
cat >"$dir/runner" <<'SH'
#!/usr/bin/env bash
# stands in for the method run: checks the draw is already on the ledger, writes the main session's usage
n="$1"; method="$2"; out="$3"
jq -e --arg id "$YUKTA_LOOP_DRAW_ID" 'select(.type == "draw" and .id == $id)' "$YUKTA_LOOP_DIR/ledger.jsonl" >/dev/null || { echo "NO DRAW BEFORE THE RUN" >&2; exit 9; }
echo '{"result":"RESULT status=SUCCESS","total_cost_usd":1.25,"num_turns":4,"duration_ms":1000,"usage":{"input_tokens":6,"cache_creation_input_tokens":3000,"cache_read_input_tokens":8000,"output_tokens":400}}' >"$out/main.json"
echo "RESULT status=SUCCESS failed_node=- healed=- execution=abc session=yukta-dev"
echo "https://github.com/infenia/yukta/pull/9999"
SH
chmod +x "$dir/runner"
YUKTA_LOOP_RUNNER="$dir/runner" "$loop" pick 400 >"$dir/pick.out" 2>&1 || fail "pick failed: $(cat "$dir/pick.out")"
out="$(jq -s '[.[] | select(.type == "outcome")] | last' "$YUKTA_LOOP_DIR/ledger.jsonl")"
[[ "$(jq -r .result <<<"$out")" == "RESULT status=SUCCESS failed_node=- healed=- execution=abc session=yukta-dev" ]] || fail "outcome keeps the whole RESULT line: $out"
[[ "$(jq -r .pr_url <<<"$out")" == *pull/9999 ]] || fail "outcome pr: $out"
[[ "$(jq -r .cost_usd <<<"$out")" == 1.25 ]] || fail "outcome cost: $out"
[[ "$(jq -r '.main."gen_ai.usage.cache_read.input_tokens"' <<<"$out")" == 8000 ]] || fail "outcome tokens use the OTel names: $out"
[[ "$("$loop" total)" == '$1.25 of $60' ]] || fail "total: $("$loop" total)"
[[ "$(jq -s '[.[] | select(.type == "analysis")] | length' "$YUKTA_LOOP_DIR/ledger.jsonl")" == 0 ]] || fail "the analyzer ran although YUKTA_LOOP_ANALYZE=off"

# a pick of an issue that is not yours, or not Ready, draws nothing
issue 401 "$small" '[]' "someone"
issue 402 "$small"; echo 402 >"$dir/notready"
before="$(wc -l <"$YUKTA_LOOP_DIR/ledger.jsonl")"
YUKTA_LOOP_RUNNER="$dir/runner" "$loop" pick 401 >"$dir/p.out" 2>&1 && fail "picked an issue assigned to someone else"
grep -q 'not assigned' "$dir/p.out" || fail "message for 401: $(cat "$dir/p.out")"
YUKTA_LOOP_RUNNER="$dir/runner" "$loop" pick 402 >"$dir/p.out" 2>&1 && fail "picked an issue that is not Ready"
grep -q 'not Ready' "$dir/p.out" || fail "message for 402: $(cat "$dir/p.out")"
[[ "$(wc -l <"$YUKTA_LOOP_DIR/ledger.jsonl")" == "$before" ]] || fail "a refused issue wrote to the ledger"

# --- next: assigned and Ready issues by priority, with size; not-Ready and someone else's are left out
mkdir -p "$dir/g2/issues" "$dir/g2/prio"
cp "$dir/gh" "$dir/g2/gh"
idir="$dir/g2/issues"
issue 501 "$medium"; echo P1 >"$dir/g2/prio/501"
issue 502 "$small"; echo P0 >"$dir/g2/prio/502"
issue 503 "$large"; echo P3 >"$dir/g2/prio/503"
issue 504 "$small"; echo 504 >"$dir/g2/notready"
issue 505 "$small" '[]' someone
unset idir
nxt="$(YUKTA_GH="$dir/g2/gh" "$loop" next)"
[[ "$(awk 'NR > 1 { printf "%s ", $1 }' <<<"$nxt")" == "#502 #501 #503 " ]] || fail "next order or content: $nxt"
grep -q '^#502 .*P0 .*S ' <<<"$nxt" || fail "next row for 502: $nxt"
grep -q '^#501 .*P1 .*M ' <<<"$nxt" || fail "next row for 501: $nxt"
grep -q '^#503 .*P3 .*L ' <<<"$nxt" || fail "next row for 503: $nxt"

# --- budget: under the limit it runs, at the limit it stops and says so
issue 410 "$small"
echo '{"type":"outcome","issue":1,"method":"B","cost_usd":58.5,"analyzer_cost_usd":0.5}' >>"$YUKTA_LOOP_DIR/ledger.jsonl"
[[ "$("$loop" total)" == '$60.25 of $60' ]] || fail "total with analyzer cost: $("$loop" total)"
YUKTA_LOOP_RUNNER="$dir/runner" "$loop" pick 410 >"$dir/p.out" 2>&1 && fail "picked over the budget"
grep -q 'review it' "$dir/p.out" || fail "budget message: $(cat "$dir/p.out")"
YUKTA_LOOP_BUDGET_TOTAL=100 YUKTA_LOOP_RUNNER="$dir/runner" "$loop" pick 410 >/dev/null 2>&1 || fail "a raised budget still stops"

echo "ok   loop"
