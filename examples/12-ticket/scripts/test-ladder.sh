#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Offline test of the retry ladder, heal, revision, budgets and approvals (#1882; design 13.1 items 8, 10, 11, 12, 23 and 15.13 items 27, 33):
# generated workflows walked against a throwaway repo, a fake gh and a fake claude, plus the decision rules called directly.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
d="$(mktemp -d)"; trap 'rm -rf "$d"' EXIT
source "$here/scripts/test-common.sh"
ev() { jq -r "$1" "$run/events.jsonl"; }

# --- 8. BLOCKED -> architect revision: completed steps untouched, spec v2, the run finishes
walk twostep true; ((rc == 0)) || fail "8: a blocked step is revised and the run finishes: $out $(tail -3 "$d/err")"
[[ -e "$run/spec/spec.v2.json" && "$(jq .spec_version "$run/spec/spec.json")" == 2 ]] || fail "8: spec v2 is created"
[[ "$(ev 'select(.role=="implementer" and .step_id=="S1") | .step_id' | wc -l)" == 1 ]] || fail "8: the completed step S1 is not run again"
[[ "$(ev 'select(.role=="architect_revise")|.role' | wc -l)" -ge 1 ]] || fail "8: the architect was called for the revision"
jq -e '.status == "DONE"' "$run/steps/S1/status.json" "$run/steps/S2/status.json" >/dev/null || fail "8: both steps done"
git -C "$d/yukta-issue-7" log --format=%s | grep -c "(#7 S1)" | grep -qx 1 || fail "8: S1 is committed once"
# a revision may not replace a completed step
cp -r "$run" "$d/run2"; jq '.replace_steps=[{"id":"S1"}]' "$run/spec/revision-result.json" >"$d/run2/spec/revision-result.json"
"$here/ticketkit" ladder apply-revision "$d/run2" >/dev/null 2>&1 && fail "8: completed steps are immutable"

# --- 10. three failed attempts: attempt 2 on the same model, attempt 3 one tier up, then the architect revises (and, with every revision used, a person)
walk happy false; ((rc != 0)) || fail "10: a gate that never passes must end the run"
models="$(jq -r 'select(.role=="implementer") | .model' "$run/events.jsonl" | head -3 | tr '\n' ' ')"
[[ "$models" == "haiku haiku sonnet " ]] || fail "10: attempts 1 and 2 on haiku, attempt 3 one tier up: $models"
grep -q "NODE escalate1 success" <<<"$out" || fail "10: after attempt 3 the architect revises"
[[ "$(jq .count "$run/revisions.json")" == 2 ]] || fail "10: two revisions at most"
grep -q "NODE human" <<<"$out" || fail "10: the third escalation goes to a person"
[[ "$(grep -c item-edit "$d/gh.log")" == 2 ]] || fail "10: the issue is claimed, then returned to Ready"

# --- 11. a budget over its limit pauses the run with a human packet; it does not fail, and a person's approval continues it
cat >"$d/gate-once.sh" <<'G'
#!/usr/bin/env bash
[[ -e "$GATE_FLAG" ]] && exit 0
touch "$GATE_FLAG"; exit 1
G
chmod +x "$d/gate-once.sh"; export GATE_FLAG="$d/gate.flag"
rm -f "$GATE_FLAG"; TEST_ASK=approve FAKE_COST=1.5 walk happy "bash $d/gate-once.sh"
((rc == 0)) || fail "11: an approved pause resumes and finishes: $out"
grep -q "ASK ask_pause1" "$d/err" && [[ -s "$run/human-packet.md" ]] || fail "11: the run pauses with a human packet"
grep -q "over the" "$run/human-packet.md" || fail "11: the packet says which budget: $(cat "$run/human-packet.md")"
[[ "$(jq .factor "$run/extension.json")" == 2 ]] || fail "11: the approval doubles the budgets"
rm -f "$GATE_FLAG"; TEST_ASK=reject FAKE_COST=1.5 walk happy "bash $d/gate-once.sh"
((rc != 0)) && grep -q "NODE paused_rejected1" <<<"$out" || fail "11: a rejected pause stops the run"
grep -q "item-edit" "$d/gh.log" && [[ "$(grep -c item-edit "$d/gh.log")" == 1 ]] || fail "11: a pause is not a failure: the issue stays claimed"

# --- heal: a mechanical stage is healed (yk check), then the step passes without another attempt
cat >"$d/gate-style.sh" <<'G'
#!/usr/bin/env bash
[[ -e "$GATE_FLAG" ]] && exit 0
touch "$GATE_FLAG"; echo "RESULT status=FAILED failed_node=style healed=- execution=e session=s wall=1 cost=\$0 bytes=1"; exit 1
G
chmod +x "$d/gate-style.sh"; rm -f "$GATE_FLAG"
walk happy "bash $d/gate-style.sh"; ((rc == 0)) || fail "heal: $out"
grep -q "NODE heal1 success" <<<"$out" && grep -q "NODE validate_heal1 success" <<<"$out" || fail "heal: style goes to heal and is validated again"
[[ "$(ls -d "$run"/steps/S1/attempt-* | wc -l)" == 1 ]] || fail "heal: a heal is not an attempt"
# --- 33. a stage that also fails on clean origin/main is a baseline failure, not the implementer's
rm -f "$GATE_FLAG"; BASELINE=false walk happy false; ((rc == 0)) || fail "33: a baseline failure does not fail the step: $out"
jq -e '.baseline_failure == true' "$run/steps/S1/status.json" >/dev/null || fail "33: recorded as baseline_failure"
[[ "$(ls -d "$run"/steps/S1/attempt-* | wc -l)" == 1 ]] || fail "33: not charged as another attempt"

# --- decision rules, called directly: a hand-built run
mk() { # stage class [result-json] -> $r with attempt-1
  r="$d/r$RANDOM"; mkdir -p "$r/spec" "$r/steps/S1/attempt-1" "$r/review"; cp "$run/project.json" "$run/routing.json" "$run/ticket.json" "$r/"; : >"$r/events.jsonl"
  cp "$run/spec/spec.json" "$r/spec/spec.json"; git -C "$d/yukta-issue-7" rev-parse HEAD >"$r/steps/S1/base"
  jq -n --arg s "$1" --arg c "$2" '{step_id:"S1",attempt:1,stage:$s,class:$c,exit_code:1,message:"m",failing:[],frames:[]}' >"$r/steps/S1/attempt-1/failure.json"
  [[ -z "${3:-}" ]] || echo "$3" >"$r/steps/S1/attempt-1/result.json"
}
act() { "$here/ticketkit" ladder decide "$r" S1 --worktree "$d/yukta-issue-7" "$@" | jq -r .action; }
mk licenses license_fail; [[ "$(act)" == human ]] || fail "licenses go to a person"
mk security test_fail; [[ "$(act)" == human ]] || fail "security goes to a person"
mk compile typecheck_fail; [[ "$(act)" == retry ]] || fail "attempt 1 retries on the same model"
mkdir -p "$r/steps/S1/attempt-2"; cp "$r/steps/S1/attempt-1/failure.json" "$r/steps/S1/attempt-2/"; [[ "$(act)" == tier_up ]] || fail "attempt 2 failing: attempt 3 one tier up"
[[ "$(jq -r .model "$r/steps/S1/model.json")" == sonnet ]] || fail "model.json records the tier"
mkdir -p "$r/steps/S1/attempt-3"; cp "$r/steps/S1/attempt-1/failure.json" "$r/steps/S1/attempt-3/"; [[ "$(act)" == revise ]] || fail "attempt 3 failing: revise"
mk timeout timeout; [[ "$(act)" == retry ]] || fail "a timeout is rerun once"; mkdir -p "$r/steps/S1/attempt-2"; cp "$r/steps/S1/attempt-1/failure.json" "$r/steps/S1/attempt-2/"; [[ "$(act)" == human ]] || fail "a second timeout goes to a person"
mk style lint_fail; [[ "$(act)" == heal ]] || fail "style is healed"; [[ "$(act --after-heal)" == revise ]] || fail "still failing after heal: revise"
mk spotless format_fail; [[ "$(act)" == retry ]] || fail "format_fail: retry once"
mk x blocked_reported '{"step_id":"S1","status":"BLOCKED","blocked":{"category":"spec_conflict","detail":"d"},"notes":""}'; [[ "$(act)" == revise ]] || fail "BLOCKED spec_conflict: revise"
mk x blocked_reported '{"step_id":"S1","status":"BLOCKED","blocked":{"category":"missing_context","detail":"d"},"notes":""}'; jq -e '.trigger == "step_blocked"' <("$here/ticketkit" ladder decide "$r" S1 --worktree "$d/yukta-issue-7") >/dev/null || fail "BLOCKED is a step_blocked revision"

# --- 27. heal edits a locked test and a file outside write_files: reverted and recorded
mk style lint_fail; echo '{"core/src/test/java/LockedTest.java":"x"}' >"$r/spec/locked.json"
wt="$d/yukta-issue-7"; mkdir -p "$wt/core/src/test/java"; echo 'class LockedTest {}' >"$wt/core/src/test/java/LockedTest.java"; git -C "$wt" add -A; git -C "$wt" commit -q -m locked; git -C "$wt" rev-parse HEAD >"$r/steps/S1/base"
TICKET_HEAL_CMD="echo changed >core/src/test/java/LockedTest.java; echo x >core/src/main/java/Other.java; echo ok >core/src/main/java/A.java" "$here/ticketkit" ladder heal "$r" S1 --worktree "$wt" >"$d/heal.out"
[[ ! -e "$wt/core/src/main/java/Other.java" && "$(cat "$wt/core/src/test/java/LockedTest.java")" == "class LockedTest {}" ]] || fail "27: the outside file and the locked test are reverted"
[[ "$(cat "$wt/core/src/main/java/A.java")" == ok ]] || fail "27: an edit inside write_files stays"
jq -e '.reverted | map(.why) | sort == ["locked test", "outside write_files"]' "$d/heal.out" >/dev/null && [[ -s "$r/steps/S1/scope-violations.jsonl" ]] || fail "27: recorded as a scope violation"
grep -q '"scope_violation": 1' "$r/events.jsonl" || fail "27: the event log has it"

# --- 12 and 23. the verdict: code overrides the reviewer
verdict() { rm -rf "$d/rv"; mkdir -p "$d/rv/review" "$d/rv/spec"; cp "$run/spec/spec.json" "$d/rv/spec/"; echo "$1" >"$d/rv/review/result.json"; "$here/ticketkit" ladder review-verdict "$d/rv" >/dev/null; jq -r .verdict "$d/rv/review/result.json"; }
[[ "$(verdict '{"verdict":"APPROVE","criteria_results":[{"id":"AC1","met":true,"evidence":"e"}],"findings":[{"severity":"blocker","path":"a","issue":"i","suggested_action":"s"}]}')" == CHANGES_REQUESTED ]] || fail "12: a blocker overrides APPROVE"
[[ "$(verdict '{"verdict":"APPROVE","criteria_results":[{"id":"TESTS_FAITHFUL","met":false,"evidence":"e"}],"findings":[]}')" == CHANGES_REQUESTED ]] || fail "23: TESTS_FAITHFUL not met overrides APPROVE"
jq -e '.findings[0].severity == "major" and (.verdict == "CHANGES_REQUESTED")' "$d/rv/review/result.json" >/dev/null || fail "23: it becomes a major finding"
[[ "$(jq -r .trigger "$d/rv/escalation.json")" == review_findings ]] || fail "23: a revision for review_findings is requested"
[[ "$(verdict '{"verdict":"APPROVE","criteria_results":[{"id":"AC1","met":true,"evidence":"e"}],"findings":[{"severity":"minor","path":"a","issue":"i","suggested_action":"s"}]}')" == APPROVE ]] || fail "a minor finding does not block"
echo "OK test-ladder"
