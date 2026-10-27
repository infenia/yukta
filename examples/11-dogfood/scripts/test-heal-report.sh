#!/usr/bin/env bash
# Offline test of heal-report.py (report, analyze, show) on a synthetic event log.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
rep() { python3 -I "$here/heal-report.py" "$@" --events "$dir/events.jsonl"; }

# no log yet
out="$(rep report)"; grep -q 'no heal events' <<<"$out" || fail "empty log: $out"

python3 -I - "$dir/events.jsonl" <<'PY'
import json, sys
ev = []
def e(run, n, event, **kw):
    ev.append({"v": 1, "ts": f"2026-10-2{run[0] if run[0].isdigit() else 5}T10:{n:02d}:00Z", "run": run, "session": "check-x", "project": "yukta", "event": event, **kw})
# run 1: tests fail, triage says moderate (sonnet/low), sonnet/low does nothing, sonnet/medium fixes
e("1aaaaaaaa", 1, "stage", stage="tests", secs=120, exit=1, ok=False, sig="s1", head="OrderServiceTest > shouldApplyDiscount FAILED")
e("1aaaaaaaa", 2, "triage", stage="tests", route="level", label="moderate", p=0.41, start="sonnet/low", model="kev:4b-q4_K_M", secs=22.0, chars=3900, fallback=None)
e("1aaaaaaaa", 3, "fix", stage="tests", attempt=1, model="sonnet", effort="low", fixed=False, claimed=False, files=[], cost=0.2, secs=300, usage={"input": 10, "cache_creation": 100, "cache_read": 900, "output": 20}, summary="cannot")
e("1aaaaaaaa", 4, "recheck", stage="tests", secs=110, ok=False)
e("1aaaaaaaa", 5, "fix", stage="tests", attempt=2, model="sonnet", effort="medium", fixed=True, claimed=True, files=["core/A.java"], cost=0.5, secs=400, usage={"input": 10, "cache_creation": 100, "cache_read": 900, "output": 20}, summary="fixed")
e("1aaaaaaaa", 6, "recheck", stage="tests", secs=115, ok=True)
e("1aaaaaaaa", 7, "verify", secs=600, ok=True)
e("1aaaaaaaa", 8, "result", status="SUCCESS", failed_node="-", healed="tests:sonnet/medium", secs=1700, scope="core", durations={"tests": 800})
# run 2: triage errors (fallback), haiku/low fixes a file outside the scoped module, run says healed=-
e("2bbbbbbbb", 1, "stage", stage="compile", secs=30, exit=1, ok=False, sig="s2", head="error: ';' expected")
e("2bbbbbbbb", 2, "triage", stage="compile", route="error", start="sonnet/medium", model="kev:4b-q4_K_M", secs=60.0, chars=900, fallback="error", error="HTTP 500 after 60s")
e("2bbbbbbbb", 3, "fix", stage="compile", attempt=1, model="sonnet", effort="medium", fixed=True, claimed=True, files=["web/B.java"], cost=0.3, secs=90, guard=None, summary="ok")
e("2bbbbbbbb", 4, "result", status="SUCCESS", failed_node="-", healed="-", secs=400, scope="core")
# run 3: gives up, guard hit; same failure as run 1 twice more
for i, r in enumerate(("3ccccccc", "4ddddddd")):
    e(r, 1, "stage", stage="tests", secs=100, exit=1, ok=False, sig="s1", head="OrderServiceTest > shouldApplyDiscount FAILED")
e("3ccccccc", 2, "triage", stage="tests", route="uncertain", p=0.2, start="sonnet/medium", model="kev:4b-q4_K_M", secs=20.0, chars=4000, fallback="uncertain")
e("3ccccccc", 3, "fix", stage="tests", attempt=1, model="opus", effort="high", fixed=False, claimed=True, files=["config/pmd.xml"], cost=1.5, secs=900, guard="touched protected path: config/pmd.xml.", summary="x")
e("3ccccccc", 4, "giveup", stage="tests")
e("3ccccccc", 5, "result", status="FAILURE", failed_node="tests", healed="-", secs=2500, scope="all")
e("2bbbbbbbb", 5, "result", status="SUCCESS", failed_node="-", healed="-", secs=400, scope="core")  # written twice: counted once
open(sys.argv[1], "w").write("\n".join(json.dumps(x) for x in ev) + "\n")
PY

out="$(rep report)"
grep -q 'Runs: 4 with events, 3 finished' <<<"$out" || fail "runs line: $out"
grep -q 'SUCCESS' <<<"$out" && grep -q "'FAILURE': 1" <<<"$out" || fail "status counts: $out"
grep -qE 'Heals: 3 started, 2 solved, 1 gave up' <<<"$out" || fail "heals: $out"
grep -q 'solved at: sonnet/medium x2' <<<"$out" || fail "solved at: $out"
grep -qE "Triage: 3 call\(s\), routes \{'level': 1, 'error': 1, 'uncertain': 1\}" <<<"$out" || fail "triage line: $out"
grep -q "fallbacks {'error': 1, 'uncertain': 1}" <<<"$out" || fail "fallbacks: $out"
grep -qE 'sonnet/low +1 +0 +0.20' <<<"$out" || fail "cost row sonnet/low: $out"
grep -qE 'opus/high +1 +0 +1.50' <<<"$out" || fail "cost row opus/high: $out"
grep -q 'total \$2.5' <<<"$out" || fail "total cost: $out"
grep -qE '3x in 3 run\(s\), tests, healed 1x: OrderServiceTest' <<<"$out" || fail "recurring: $out"
grep -qE 'tests +3 +3' <<<"$out" || fail "stage table: $out"

# the findings, and the exit code
out="$(rep analyze || true)"
rep analyze >/dev/null && fail "analyze exits 0 with findings"
for rule in noop-fixers fixer-outside-module healed-field-mismatch triage-fallbacks guard-hits giveups repeated-failure; do
  grep -q "^$rule:" <<<"$out" || fail "no finding $rule: $out"
done
grep -q 'web/B.java' <<<"$out" || fail "outside-module evidence: $out"
grep -q 'HTTP 500 after 60s' <<<"$out" || fail "fallback evidence: $out"

# filters
[[ "$(rep show --run 2bbb | wc -l)" == 5 ]] || fail "--run"
rep report --since 2026-10-26 | grep -q 'no heal events' || fail "--since in the future"
rep report --project other | grep -q 'no heal events' || fail "--project"
rep report --json | python3 -I -c "import json,sys; d=json.load(sys.stdin); assert d['heals']['solved']==2 and len(d['findings'])>=7, d" || fail "--json"
# a clean log has no findings and exits 0
python3 -I - "$dir/events.jsonl" "$dir/clean.jsonl" <<'PY'
import json, sys
ev = [json.loads(l) for l in open(sys.argv[1])]
open(sys.argv[2], "w").write(json.dumps([x for x in ev if x["run"] == "1aaaaaaaa" and x["event"] in ("stage", "recheck", "verify")][0]) + "\n")
PY
python3 -I "$here/heal-report.py" analyze --events "$dir/clean.jsonl" | grep -q 'no findings' || fail "clean log"
# events written without a run id take the run of the session's next result
printf '%s\n' \
  '{"v":1,"ts":"2026-10-25T10:01:00Z","run":"-","session":"s9","project":"yukta","event":"fix","stage":"tests","attempt":1,"model":"haiku","fixed":true,"files":[],"cost":0.1,"secs":5}' \
  '{"v":1,"ts":"2026-10-25T10:02:00Z","run":"9eeeeeeee","session":"s9","project":"yukta","event":"result","status":"SUCCESS","failed_node":"-","healed":"tests:haiku","secs":10}' >"$dir/stamp.jsonl"
python3 -I "$here/heal-report.py" show --events "$dir/stamp.jsonl" | grep -c '"run": "9eeeeeeee"' | grep -qx 2 || fail "run stamping"
echo "ok   heal report"
