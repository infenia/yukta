#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Offline test of the decision model in the ticket pipeline (#1883; design 3.2, 15.8; 13.1 item 9, 15.13 item 34) against the fake Ollama:
# shadow mode logs and the static rule decides, an unreachable or broken model falls back with one WARN line, `on` is a config change only,
# the digests hold facts and respect the cap, and the cause decision feeds the ladder.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dog="$here/../11-dogfood"
fail() { echo "FAIL $*" >&2; exit 1; }
d="$(mktemp -d)"; trap '[[ -z "${pid:-}" ]] || kill "$pid" 2>/dev/null || true; rm -rf "$d"' EXIT
python3 -I "$dog/scripts/fake-ollama.py" "$d/port" & pid=$!
for _ in $(seq 40); do [[ -s "$d/port" ]] && break; sleep 0.25; done
export YUKTA_DEV_STATE_DIR="$d/state" YUKTA_DEV_HOME="$d/home" YUKTA_DECISION_MODEL=fake YUKTA_DECISION_URL="http://127.0.0.1:$(cat "$d/port")"
unset YUKTA_DECISION
tk="$here/ticketkit"; tsv="$d/state/decisions.tsv"
# all four decisions start in shadow, with a bar, and `on` is the only change needed
for n in triage_complexity triage_risk cause merge_risk; do
  jq -e --arg n "$n" '.decisions[$n] | .mode == "shadow" and .bar.minN == 50 and .bar.minAgreement == 0.9' "$dog/decisions.json" >/dev/null || fail "$n: shadow with the bar 50 / 0.9 in decisions.json"
done
# --- a run: S/low feature in core
repo="$d/repo"; mkdir -p "$repo/core/src/main/java"; cd "$repo" && git init -q -b main . && git config user.email t@t && git config user.name t
echo 'class B {}' >core/src/main/java/B.java; git add -A && git commit -q -m init && git update-ref refs/remotes/origin/main HEAD
jq -n '{number:7,title:"feat(core): rate limit",labels:[{name:"core"}],body:"## Acceptance criteria\n- [ ] limiter rejects the 101st request\n- [ ] tests pass\n"}' >"$d/t.json"
newrun() { rm -rf "$d/state/ticket"; out="$("$tk" init --ticket-file "$d/t.json" --root "$repo" --state-dir "$d/state" --no-worktree)"; run="$(sed -n 's/^RUN_DIR=//p' <<<"$out")"; "$tk" route "$run" >/dev/null; }
newrun; echo L >"$d/port.hint"

# --- shadow: the static rule decides, the model is logged, with the static label as the outcome
r="$("$tk" decision triage "$run" 2>"$d/err")"
[[ "$(jq -r '[.label, .source] | join(" ")' <<<"$(sed -n 1p <<<"$r")")" == "S static" ]] || fail "shadow: complexity stays with the static rule: $r"
[[ "$(jq -r '.complexity + " " + .risk + " " + .decided_by' "$run/routing.json")" == "S low static" ]] || fail "shadow: routing.json is unchanged"
[[ "$(grep -c $'\tdecision\t' "$tsv")" == 2 && "$(grep -c $'\toutcome\t' "$tsv")" == 2 ]] || fail "shadow: two decisions and two outcomes are logged: $(cat "$tsv")"
grep -qP '\tdecision\ttriage_complexity\t\w+\tshadow\tL\t' "$tsv" || fail "shadow: the model said L"
python3 "$dog/scripts/decisions-report.py" "$tsv" "$dog/decisions.json" | grep -q "triage_complexity: 1 decisions, 1 with an outcome, agreement 0/1" || fail "yk stats --decisions shows the decision"
python3 "$dog/scripts/decisions-report.py" "$tsv" "$dog/decisions.json" | grep -q "triage_risk: 1 decisions" || fail "yk stats --decisions shows triage_risk"

# --- mode on is a config change only (here the environment form of "mode": "on"): the model's label is used
newrun; r="$(YUKTA_DECISION_TRIAGE_COMPLEXITY=on "$tk" decision triage "$run" 2>/dev/null | sed -n 1p)"
[[ "$(jq -r .source <<<"$r")" == model && "$(jq -r '.complexity + " " + .decided_by + " " + .models.architect.model' "$run/routing.json")" == "L model opus" ]] || fail "on: the model's L is used and the routing follows: $r $(cat "$run/routing.json")"

# --- 13.1 item 9 / 15.13 item 34: unreachable, broken or oversized: the static rule, one WARN line per decision, the run goes on
newrun; YUKTA_DECISION_URL=http://127.0.0.1:1 "$tk" decision triage "$run" >"$d/o" 2>"$d/err" || fail "an unreachable model must not fail the step"
[[ "$(grep -c 'WARN decision model unavailable' "$d/err")" == 2 ]] || fail "one WARN line per decision: $(cat "$d/err")"
[[ "$(jq -r .complexity "$run/routing.json")" == S && "$(jq -r .source <<<"$(sed -n 1p "$d/o")")" == static ]] || fail "unreachable: the static rule"
newrun; YUKTA_DECISION_MODEL=stale YUKTA_DECISION_TRIAGE_COMPLEXITY=on "$tk" decision triage "$run" >"$d/o" 2>"$d/err" || fail "a broken model must not fail the step"
grep -q 'WARN decision model unavailable' "$d/err" && [[ "$(jq -r .complexity "$run/routing.json")" == S ]] || fail "a model that cannot answer: the static rule"
newrun; YUKTA_DECISION_MAX_INPUT=10 "$tk" decision triage "$run" >/dev/null 2>"$d/err"; grep -q 'over the cap of 10' "$d/err" || fail "YUKTA_DECISION_MAX_INPUT is respected: $(cat "$d/err")"
# the digests hold facts, never a diff
python3 "$here/scripts/decision.py" inputs triage_risk "$run" - | jq -r .state | grep -qE '^(diff|\+\+\+|---|@@)' && fail "a digest must not hold a diff"
[[ "$(python3 "$here/scripts/decision.py" inputs triage_complexity "$run" - | jq -r .state)" == "kind=feature criteria=2 modules=core ecosystems=java" ]] || fail "the complexity digest"

# --- cause: tests and compile failures from attempt 2, through the ladder
rm -f "$d/port.hint"
mkrun() { # attempt-1 and attempt-2 failed with `tests`
  rm -rf "$d/cr"; cr="$d/cr"; mkdir -p "$cr/spec" "$cr/steps/S1/attempt-1" "$cr/steps/S1/attempt-2"; cp "$run/project.json" "$run/routing.json" "$run/ticket.json" "$cr/"; : >"$cr/events.jsonl"
  jq -n '{spec_version:1,steps:[{id:"S1",depends_on:[],write_files:["core/src/main/java/A.java"]}]}' >"$cr/spec/spec.json"; git -C "$repo" rev-parse HEAD >"$cr/steps/S1/base"
  for n in 1 2; do jq -n --argjson n $n '{step_id:"S1",attempt:$n,stage:"tests",class:"test_fail",exit_code:1,message:"expected false got true",failing:["T1"],frames:[]}' >"$cr/steps/S1/attempt-$n/failure.json"; done
}
act() { "$tk" ladder decide "$cr" S1 --worktree "$repo" 2>/dev/null | jq -r .action; }
mkrun; echo spec_gap >"$d/port.hint"; [[ "$(act)" == tier_up ]] || fail "cause in shadow: the ladder goes on (attempt 2 -> tier up)"
grep -qP '\tdecision\tcause\t' "$tsv" || fail "cause is logged in shadow"
mkrun; [[ "$(YUKTA_DECISION_CAUSE=on act)" == revise ]] || fail "cause spec_gap (mode on): the architect revises now"
mkrun; echo spec_conflict >"$d/port.hint"; [[ "$(YUKTA_DECISION_CAUSE=on act)" == revise ]] || fail "cause spec_conflict: revise"
mkrun; echo flaky >"$d/port.hint"; [[ "$(YUKTA_DECISION_CAUSE=on act)" == retry ]] || fail "cause flaky: rerun once unchanged"; grep -q '"flaky": 1' "$cr/events.jsonl" || fail "flaky is recorded"
mkdir -p "$cr/steps/S1/attempt-3"; cp "$cr/steps/S1/attempt-2/failure.json" "$cr/steps/S1/attempt-3/"; [[ "$(YUKTA_DECISION_CAUSE=on act)" != retry ]] || fail "flaky reruns only once"
mkrun; echo impl_bug >"$d/port.hint"; [[ "$(YUKTA_DECISION_CAUSE=on act)" == tier_up ]] || fail "cause impl_bug: the ladder goes on"
mkrun; jq '.stage="style"' "$cr/steps/S1/attempt-2/failure.json" >"$d/x" && mv "$d/x" "$cr/steps/S1/attempt-2/failure.json"; : >"$d/state/decisions.tsv"; act >/dev/null; ! grep -q $'\tcause\t' "$d/state/decisions.tsv" || fail "cause is not asked for a style failure"

# --- merge risk: logged in shadow; in mode on a high score asks for the merge gate
newrun; : >"$d/state/decisions.tsv"; echo 9 >"$d/port.hint"
[[ "$("$tk" ladder need-approval "$run" merge --worktree "$repo" 2>/dev/null | jq -r .ask)" == false ]] || fail "merge_risk in shadow changes no gate"
grep -qP '\tdecision\tmerge_risk\t\w+\tshadow\t9\t' "$tsv" || fail "merge_risk is logged in shadow"
[[ "$(YUKTA_DECISION_MERGE_RISK=on "$tk" ladder need-approval "$run" merge --worktree "$repo" 2>/dev/null | jq -r .ask)" == true ]] || fail "merge_risk 9 (mode on) asks"

# --- the triage URL is a per-machine override
source "$dog/scripts/_lib.sh"
[[ "$(triage_conf url)" == "$(jq -r .triage.url "$dog/heal.json")" ]] || fail "triage_conf: the repo default"
mkdir -p "$d/home"; echo '{"url":"http://my-lan:11434"}' >"$d/home/triage.json"; DEV_HOME="$d/home"
[[ "$(DEV_HOME="$d/home" triage_conf url)" == http://my-lan:11434 ]] || fail "triage_conf: the per-machine file"
[[ "$(YUKTA_TRIAGE_URL=http://env:1 DEV_HOME="$d/home" triage_conf url)" == http://env:1 ]] || fail "triage_conf: the environment wins"
echo "OK test-decision-ticket"
