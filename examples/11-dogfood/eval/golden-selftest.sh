#!/usr/bin/env bash
# Offline test of the golden set (golden.sh, golden-report.py): replays with a fake claude on a dummy issue, the order, the refusals,
# the budget stop and the resume, and the report's statistics on a synthetic ledger with planted effects. No server, no Claude, no spend.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
mkdir -p "$dir/issues/9001"
cp "$here/issues/selftest/issue.json" "$dir/issues/9001/issue.json"
echo "x" >"$dir/issues/9001/hidden_test.go"
printf 'MODULE=cli\nHIDDEN_DEST=eval-hidden.txt\nHIDDEN_CMD=true\nSUITE_CMD=true\n' >"$dir/issues/9001/grade.env"
base="$(git -C "$here" rev-parse HEAD)"
jq -n --arg b "$base" '{issues: [{issue: 9001, base: $b, why: "dummy"}]}' >"$dir/set.json"
export YUKTA_GOLDEN_SET="$dir/set.json" YUKTA_GOLDEN_DIR="$dir/golden" EVAL_ISSUES_DIR="$dir/issues" EVAL_CLAUDE="$here/fake-claude.sh" EVAL_YK=/bin/true \
  EVAL_SRC="$(git -C "$here" rev-parse --show-toplevel)" YUKTA_GOLDEN_SHA=abc123 YUKTA_GOLDEN_BUDGET_TOTAL=100
golden="$here/golden.sh"

# ---- status before anything ran: the set, and the loop commit is due
out="$("$golden" status)"
grep -q '#9001 base' <<<"$out" && grep -q 'Due: this loop commit (abc123)' <<<"$out" || fail "status: $out"

# ---- the plan: every method once per issue and trial, in a seeded order that differs between trials and is the same every time
plan="$("$golden" run --trials 3 --dry-run)"
[[ "$(grep -c '^  9001:' <<<"$plan")" == 6 ]] || fail "3 trials x 2 methods = 6 runs expected: $plan"
for t in 1 2 3; do
  [[ "$(grep -E "^  9001:[AC]:$t\$" <<<"$plan" | cut -d: -f2 | sort | paste -sd,)" == A,C ]] || fail "trial $t does not hold every method once: $plan"
done
[[ "$("$golden" run --trials 3 --dry-run)" == "$plan" ]] || fail "the plan is not the same twice"
plan8="$("$golden" run --trials 8 --dry-run)"
orders="$(for t in 1 2 3 4 5 6 7 8; do grep -E "^  9001:[AC]:$t\$" <<<"$plan8" | cut -d: -f2 | paste -sd,; done | sort -u | wc -l)"
(( orders >= 2 )) || fail "the method order is the same in every trial (no shuffle): $plan8"
[[ "$(YUKTA_GOLDEN_SHA=other "$golden" run --trials 3 --dry-run)" != "$plan" ]] || fail "the order does not depend on the loop commit"
echo "ok   plan"

# ---- a dirty loop commit is refused; --methods and --issues narrow the plan
YUKTA_GOLDEN_SHA=abc123-dirty "$golden" run --dry-run >"$dir/o1" 2>&1 && fail "a dirty tree was accepted"
grep -q 'a dirty tree is not a loop version' "$dir/o1" || fail "message: $(cat "$dir/o1")"
[[ "$("$golden" run --trials 1 --methods A --dry-run | grep -c '^  9001:')" == 1 ]] || fail "--methods"
"$golden" run --trials 1 --methods A,B --dry-run >"$dir/o5" 2>&1 && fail "retired method B was accepted"
grep -q 'method B is retired' "$dir/o5" || fail "message for B: $(cat "$dir/o5")"
"$golden" run --issues 4242 --dry-run >"$dir/o2" 2>&1 && fail "an issue outside the set was replayed"
echo "ok   refusals"

# ---- the budget stops a replay between runs and the next run resumes it where it stopped
YUKTA_GOLDEN_BUDGET_TOTAL=1.0 "$golden" run --trials 2 >"$dir/o3" 2>&1 || fail "a replay that stops on its budget must not fail: $(tail -5 "$dir/o3")"
grep -q 'STOP: the replay has spent' "$dir/o3" || fail "no budget stop: $(tail -5 "$dir/o3")"
[[ "$(jq -s '[.[] | select(.type == "run")] | length' "$dir/golden/ledger.jsonl")" == 1 ]] || fail "exactly one run should fit a \$1.00 budget (every run costs more): $(jq -c 'select(.type == "run") | {method, cost_usd}' "$dir/golden/ledger.jsonl")"
[[ "$(jq -s 'map(select(.type == "replay-end")) | last | .state' -r "$dir/golden/ledger.jsonl")" == stopped ]] || fail "a stopped replay is not marked stopped"
"$golden" run --trials 2 >"$dir/o4" 2>&1 || fail "the resume failed: $(tail -5 "$dir/o4")"
grep -c 'skip #9001' "$dir/o4" | grep -q '^1$' || fail "the run already done was not skipped: $(cat "$dir/o4")"
[[ "$(jq -s '[.[] | select(.type == "run")] | length' "$dir/golden/ledger.jsonl")" == 4 ]] || fail "after the resume the replay holds 4 runs"
[[ "$(jq -s '[.[] | select(.type == "replay")] | length' "$dir/golden/ledger.jsonl")" == 1 ]] || fail "the resume opened a second replay"
[[ "$(jq -s 'map(select(.type == "replay-end")) | last | .state' -r "$dir/golden/ledger.jsonl")" == complete ]] || fail "the replay did not complete"
echo "ok   budget stop and resume"

# ---- what a run records
jq -e -s '[.[] | select(.type == "run")] | all(.loop_sha == "abc123" and .replay == "abc123-1" and .issue == 9001 and .correct == true and .success == true)' "$dir/golden/ledger.jsonl" >/dev/null || fail "the run records: $(jq -c 'select(.type == "run")' "$dir/golden/ledger.jsonl" | head -2)"
jq -e -s '[.[] | select(.type == "run" and .method == "C")] | all(.backend == "cbm")' "$dir/golden/ledger.jsonl" >/dev/null || fail "method C must record its backend"
jq -e -s '[.[] | select(.type == "run" and .method == "A")] | all(.delivered == null)' "$dir/golden/ledger.jsonl" >/dev/null || fail "method A has no workflow, so no delivered"
jq -e -s '[.[] | select(.type == "run")] | map(.cost_usd) | all(. > 0)' "$dir/golden/ledger.jsonl" >/dev/null || fail "a run has no cost"
[[ -d "$dir/golden/runs/abc123/abc123-1/9001/runs/A1" ]] || fail "the run directory layout"

# ---- a complete replay is not repeated for the same loop commit; --force makes a new one
"$golden" run >"$dir/o5" 2>&1 && fail "a loop commit with a complete replay was replayed"
grep -q 'already has a complete replay' "$dir/o5" || fail "message: $(cat "$dir/o5")"
forced="$("$golden" run --trials 1 --force --dry-run)"
grep -q '^Replay abc123-2' <<<"$forced" || fail "--force should open replay abc123-2: $forced"
st="$("$golden" status)"
grep -q 'This loop commit has a complete replay' <<<"$st" || fail "status after the replay: $st"
echo "ok   replay"

# ---- the report: planted effects on a synthetic ledger
python3 -I - "$dir/synth.jsonl" 6 <<'EOF'
import json, random, sys
path, n_issues = sys.argv[1], int(sys.argv[2])
rng = random.Random(3)
rows = []
for loop, mult in (("X1", 1.0), ("X2", 1.5)):  # X2 is a loop commit that makes everything 1.5x dearer
    for trial in (1, 2, 3):
        for i in range(n_issues):
            f = 1.0 + i * 0.5
            for m, c in (("A", 1.0), ("C", 2.0)):
                ok = not (m == "C" and trial == 3)  # C fails its third trial everywhere: 2 successes in 3
                rows.append({"type": "run", "replay": loop + "-1", "loop_sha": loop, "issue": 1000 + i, "method": m, "trial": trial,
                             "cost_usd": f * c * mult * (1 + rng.uniform(-0.1, 0.1)), "success": ok, "backend": "cbm" if m == "C" else None})
open(path, "w").write("\n".join(json.dumps(r) for r in rows) + "\n")
EOF
rep() { python3 -I "$here/golden-report.py" --ledger "$dir/synth.jsonl" "$@"; }
r="$(rep --loop X1)"
grep -qE '^\| A \| 18 \| 18/18 \| 1.00 / 1.00 / 1.00 \|' <<<"$r" || fail "method A: $(grep '^| A ' <<<"$r")"
grep -qE '^\| C \| 18 \| 12/18 \| 0.67 / 0.33 / 0.00 \|' <<<"$r" || fail "pass^k of C (2 of 3 trials succeed): $(grep '^| C ' <<<"$r")"
grep -qE 'C vs A: geometric mean cost ratio \*\*x(1\.9[0-9]|2\.0[0-9]|2\.1[0-9])\*\* over 6 issues' <<<"$r" || fail "the planted x2 of C over A: $(grep 'C vs A:' <<<"$r")"
grep -q 'clear (interval excludes x1.00): yes' <<<"$r" || fail "the interval should exclude 1"
grep -qE '^\| #1000 \| C \| 3 \| 2/3 \| \$[0-9.]+ \| \$[0-9.]+\.\.\$[0-9.]+ \| cbm \|' <<<"$r" || fail "per issue row with the backend: $(grep '#1000 | C' <<<"$r")"
grep -qE '^\| A \| 18 \| 18/18 \| .* \| [0-9]+% \|' <<<"$r" || fail "the cost spread (CV) is missing"
# the default loop is the one with the latest run, and --against compares two loop commits on the same issues
[[ "$(rep | head -1)" == "# Golden-set report, loop commit X2" ]] || fail "default loop: $(rep | head -1)"
ag="$(rep --loop X2 --against X1)"
grep -qE 'Method A, X2 vs X1: geometric mean cost ratio \*\*x1\.[45][0-9]\*\* over 6 issues' <<<"$ag" || fail "the planted x1.5 between loop commits: $(grep 'Method A, X2' <<<"$ag")"
[[ "$(rep --loop X2 --against X1)" == "$ag" ]] || fail "the report is not deterministic"
# 3 issues: numbers, no verdict
python3 -I - "$dir/synth3.jsonl" 3 <<'EOF'
import json, sys
rows = []
for trial in (1, 2, 3):
    for i in range(int(sys.argv[2])):
        for m, c in (("A", 1.0), ("C", 2.0)):
            rows.append({"type": "run", "replay": "X1-1", "loop_sha": "X1", "issue": 1000 + i, "method": m, "trial": trial, "cost_usd": c * (1 + i), "success": True})
open(sys.argv[1], "w").write("\n".join(json.dumps(r) for r in rows) + "\n")
EOF
r3="$(python3 -I "$here/golden-report.py" --ledger "$dir/synth3.jsonl")"
grep -q 'No verdict\.\*\* C vs A: only 3 issue(s) have both sides' <<<"$r3" || fail "3 issues must give no verdict: $(grep -i 'verdict' <<<"$r3")"
! grep -q 'geometric mean cost ratio' <<<"$r3" || fail "a ratio was claimed from 3 issues"
[[ "$(python3 -I "$here/golden-report.py" --ledger "$dir/none.jsonl")" == *"No golden-set run"* ]] || fail "empty ledger"
echo "ok   report"
echo "ok   golden set"
