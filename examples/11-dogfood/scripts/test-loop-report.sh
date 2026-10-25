#!/usr/bin/env bash
# Offline test of yk loop report and yk loop sync: a synthetic ledger with a planted effect (method B costs twice method A), small
# samples that must get no verdict, runs that must stay out of the comparison, determinism, and sync against a fake gh.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
export YUKTA_LOOP_DIR="$dir/loop" YUKTA_REPO_ROOT="$dir/repo" YUKTA_GH="$dir/gh" YUKTA_LOOP_BUDGET_TOTAL=60
mkdir -p "$dir/loop" "$dir/repo"
loop="$here/loop.sh"

# make_ledger FILE N-PER-METHOD: A costs 1 x size factor, B twice that, noise of 15%, sizes S M L in turn
make_ledger() {
  python3 -I - "$1" "$2" <<'EOF'
import json, random, sys
path, n = sys.argv[1], int(sys.argv[2])
rng = random.Random(7)
factor = {"S": 1.0, "M": 2.0, "L": 4.0}
rows, k = [], 0
for method, mult in (("A", 1.0), ("B", 2.0)):
    for i in range(n):
        size = "SML"[i % 3]
        k += 1
        did = "d%02d" % k
        rows.append({"type": "draw", "id": did, "issue": 100 + k, "size": size, "method": method, "forced": False, "loop_changing": False, "loop_sha": "aaa111"})
        cost = factor[size] * mult * (1 + rng.uniform(-0.15, 0.15))
        res = "RESULT status=SUCCESS" if not (method == "A" and i == 0) else "RESULT status=ESCALATE"
        rows.append({"type": "outcome", "draw": did, "issue": 100 + k, "method": method, "result": res + " x", "wall_s": 100 * factor[size], "cost_usd": cost,
                     "main": {"gen_ai.usage.input_tokens": 100, "gen_ai.usage.cache_read.input_tokens": 1000}, "sub": {}})
with open(path, "w") as f:
    for r in rows:
        f.write(json.dumps(r) + "\n")
EOF
}
extras() { # FILE: runs that must stay out of the comparison, and a restarted run whose costs add up
  python3 -I - "$1" <<'EOF'
import json, sys
rows = [
  {"type": "draw", "id": "f1", "issue": 901, "size": "S", "method": "A", "forced": True, "loop_changing": False, "loop_sha": "aaa111"},
  {"type": "outcome", "draw": "f1", "issue": 901, "method": "A", "result": "RESULT status=SUCCESS x", "wall_s": 1, "cost_usd": 100.0, "main": {}, "sub": {}},
  {"type": "draw", "id": "l1", "issue": 902, "size": "S", "method": "B", "forced": False, "loop_changing": True, "loop_sha": "aaa111"},
  {"type": "outcome", "draw": "l1", "issue": 902, "method": "B", "result": "RESULT status=SUCCESS x", "wall_s": 1, "cost_usd": 100.0, "main": {}, "sub": {}},
  {"type": "draw", "id": "r1", "issue": 903, "size": "S", "method": "A", "forced": False, "loop_changing": False, "loop_sha": "bbb222"},
  {"type": "outcome", "draw": "r1", "issue": 903, "method": "A", "result": "RESULT status=ESCALATE x", "wall_s": 10, "cost_usd": 1.0, "main": {}, "sub": {}},
  {"type": "outcome", "draw": "r1", "issue": 903, "method": "A", "result": "RESULT status=SUCCESS x", "wall_s": 20, "cost_usd": 1.5, "main": {}, "sub": {}},
  {"type": "draw", "id": "u1", "issue": 904, "size": "S", "method": "B", "forced": False, "loop_changing": False, "loop_sha": "aaa111"},
]
with open(sys.argv[1], "a") as f:
    for r in rows:
        f.write(json.dumps(r) + "\n")
EOF
}
report() { python3 -I "$here/loop-report.py" --ledger "$1" "${@:2}"; }

# ---- 10 runs per method: the planted 2x is recovered, with an interval that excludes 1; the cheap method's reliability shows
make_ledger "$dir/big.jsonl" 10; extras "$dir/big.jsonl"
out="$(report "$dir/big.jsonl")"
grep -qE '^\| B vs A \| x(1\.[6-9][0-9]|2\.[0-4][0-9]) \| \[x[0-9.]+\.\.x[0-9.]+\] \| yes \|' <<<"$out" || fail "the planted x2 of B over A was not recovered: $(grep 'B vs A' <<<"$out")"
grep -qE '^\| A \| 11 \| 10/11 ' <<<"$out" || fail "method A row (10 runs + the restarted one, 10 delivered): $(grep '^| A ' <<<"$out")"
grep -qE '^\| B \| 10 \| 10/10 ' <<<"$out" || fail "method B row: $(grep '^| B ' <<<"$out")"
! grep -q 'No verdict' <<<"$out" || fail "a verdict was withheld with n >= 8"
grep -q 'Pareto set' <<<"$out" || fail "no Pareto line"
[[ "$(grep -oE 'median cost \[90% interval\]' <<<"$out" | wc -l)" == 1 ]] || fail "the per-method table"
# the forced and the loop-changing runs are left out and listed apart; their $100 is not in any median
grep -q '#901 | A | forced' <<<"$out" && grep -q '#902 | B | changes the loop' <<<"$out" || fail "the left-out runs are not listed: $(sed -n '/Left out/,/After the pull/p' <<<"$out")"
! grep -qE '\$100\.00 \[|\$100\.00 \.\.' <<<"$out" || fail "a left-out run is in an interval"
grep -q 'A \| ' <<<"$out" && ! grep -qE '^\| A \| 1[2-9]' <<<"$out" || fail "an unfinished or left-out run was counted"
grep -q '#903 | S | A | - | bbb222 | \$2.50 ' <<<"$out" || fail "the restarted run should add its costs up and keep its loop commit: $(grep '#903' <<<"$out")"
grep -q 'restarted' <<<"$out" || fail "a restarted run is not flagged"
grep -q 'spans 2 loop commits' <<<"$out" || fail "no warning that the comparison spans loop commits: $(sed -n 1,6p <<<"$out")"
# --loop restricts the comparison to one loop commit
only="$(report "$dir/big.jsonl" --loop aaa111)"
! grep -q 'spans' <<<"$only" && grep -qE '^\| A \| 10 \| 9/10 ' <<<"$only" || fail "--loop aaa111: $(grep -E '^\| A |spans' <<<"$only")"
# the same ledger gives the same report
[[ "$(report "$dir/big.jsonl")" == "$out" ]] || fail "the report is not deterministic"
echo "ok   planted effect, exclusions, restarts, determinism"

# ---- 4 runs per method: numbers, but no verdict
make_ledger "$dir/small.jsonl" 4
out="$(report "$dir/small.jsonl")"
grep -q '\*\*No verdict\.\*\* method A has n=4, method B has n=4' <<<"$out" || fail "no verdict expected: $(sed -n '/Comparing/,/Left out/p' <<<"$out")"
! grep -q 'B vs A' <<<"$out" && ! grep -q 'Pareto set' <<<"$out" || fail "a comparison was printed with n=4"
grep -qE '^\| A \(n<8\) \| 4 ' <<<"$out" || fail "the small sample is not flagged in the method table"
[[ "$(report "$dir/small.jsonl" --min-n 4 | grep -c 'B vs A')" == 1 ]] || fail "--min-n 4 should allow the comparison"
echo "ok   no verdict below n=8"

# ---- an empty or missing ledger
[[ "$(report "$dir/none.jsonl")" == *"No finished runs"* ]] || fail "a missing ledger"
echo "ok   empty ledger"

# ---- sync: CI, review comments, human commits, merged, and the issues the analyzer filed
cp "$dir/small.jsonl" "$YUKTA_LOOP_DIR/ledger.jsonl"
python3 -I - "$YUKTA_LOOP_DIR/ledger.jsonl" <<'EOF'
import json, sys
rows = []
# three runs get a pull request; the analyzer filed three issues
for did, pr in (("d01", 11), ("d02", 12), ("d05", 13)):
    rows.append({"type": "outcome", "draw": did, "issue": 1, "method": "A", "result": "RESULT status=SUCCESS x", "pr_url": "https://github.com/infenia/yukta/pull/%d" % pr, "wall_s": 1, "cost_usd": 0, "main": {}, "sub": {}})
rows.append({"type": "analysis", "draw": "d01", "issue": 101, "dry_run": False, "analyzer_cost_usd": 0.1,
             "filed": [{"action": "file", "url": "https://github.com/infenia/yukta/issues/501"}, {"action": "file", "url": "https://github.com/infenia/yukta/issues/502"},
                       {"action": "file", "url": "https://github.com/infenia/yukta/issues/503"}, {"action": "comment", "issue": 1234}]})
with open(sys.argv[1], "a") as f:
    for r in rows:
        f.write(json.dumps(r) + "\n")
EOF
cat >"$dir/gh" <<'SH'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")" && pwd)"
echo "$*" >>"$here/gh.log"
case "$1 $2" in
  "pr view")
    n="${3##*/}"
    case "$n" in
      11) jq -nc '{state: "MERGED", mergedAt: "2026-10-25T12:00:00Z", createdAt: "2026-10-25T09:00:00Z", reviews: [{}], comments: [{}, {}],
          statusCheckRollup: [{status: "COMPLETED", conclusion: "SUCCESS"}, {status: "COMPLETED", conclusion: "SKIPPED"}],
          commits: [{messageBody: "x\n\nCo-Authored-By: Claude <noreply@anthropic.com>"}, {messageBody: "a human fix"}]}' ;;
      12) jq -nc '{state: "OPEN", mergedAt: null, createdAt: "2026-10-25T09:00:00Z", reviews: [], comments: [],
          statusCheckRollup: [{status: "COMPLETED", conclusion: "SUCCESS"}, {status: "COMPLETED", conclusion: "FAILURE"}],
          commits: [{messageBody: "Co-Authored-By: Claude <noreply@anthropic.com>"}]}' ;;
      13) jq -nc '{state: "OPEN", mergedAt: null, createdAt: "2026-10-25T09:00:00Z", reviews: [], comments: [],
          statusCheckRollup: [{status: "IN_PROGRESS", conclusion: ""}], commits: [{messageBody: "Co-Authored-By: Claude <noreply@anthropic.com>"}]}' ;;
    esac ;;
  "api repos/infenia/yukta/pulls/11/comments") echo 3 ;;
  "api repos/infenia/yukta/pulls/"*) echo 0 ;;
  "issue view")
    case "$3" in
      501) echo '{"state":"CLOSED","stateReason":"COMPLETED"}' ;;
      502) echo '{"state":"CLOSED","stateReason":"NOT_PLANNED"}' ;;
      503) echo '{"state":"OPEN","stateReason":""}' ;;
    esac ;;
  *) echo "fake gh: unsupported: $*" >&2; exit 1 ;;
esac
SH
chmod +x "$dir/gh"
out="$("$loop" sync)" || fail "sync failed: $out"
s="$(jq -s '[.[] | select(.type == "sync")]' "$YUKTA_LOOP_DIR/ledger.jsonl")"
[[ "$(jq -c 'map({draw, ci, review_comments, human_commits, merged}) | sort_by(.draw)' <<<"$s")" == '[{"draw":"d01","ci":"success","review_comments":6,"human_commits":1,"merged":true},{"draw":"d02","ci":"failure","review_comments":0,"human_commits":0,"merged":false},{"draw":"d05","ci":"pending","review_comments":0,"human_commits":0,"merged":false}]' ]] || fail "sync records: $(jq -c . <<<"$s")"
[[ "$(jq -r '.[] | select(.draw == "d01") | .lead_time_h' <<<"$s")" == 3 ]] || fail "lead time: $(jq -c . <<<"$s")"
[[ "$(jq -s '[.[] | select(.type == "issue-sync")] | map("\(.issue):\(.state):\(.state_reason)") | sort | join(",")' -r "$YUKTA_LOOP_DIR/ledger.jsonl")" == "501:closed:completed,502:closed:not_planned,503:open:" ]] || fail "issue-sync records"
: >"$dir/gh.log"
"$loop" sync >/dev/null || fail "second sync failed"
! grep -q 'pr view https://github.com/infenia/yukta/pull/11' "$dir/gh.log" || fail "a merged PR was read again"
grep -q 'pr view https://github.com/infenia/yukta/pull/12' "$dir/gh.log" || fail "an open PR must be read again"
r="$("$loop" report)"
grep -qE '^\| A \| 2 \| 1/2 \| 3 \| 0.5 \| 1/2 \|' <<<"$r" && grep -qE '^\| B \| 1 \| 0/1 \| 0 \| 0 \| 0/1 \|' <<<"$r" || fail "the report does not show what happened after the PRs: $(sed -n '/After the pull/,/The analyzer/p' <<<"$r")"
grep -q 'filed 3 new issue(s): 1 done, 1 closed without being done, 1 still open' <<<"$r" || fail "analyzer outcome: $(sed -n '/The analyzer/,$p' <<<"$r")"
grep -q 'Precision so far: 1/2 of the decided ones' <<<"$r" || fail "precision"
grep -q 'commented on known issues 1 time(s)' <<<"$r" || fail "comments"
echo "ok   sync and the analyzer's precision"
echo "ok   loop report"
