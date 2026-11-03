#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Offline test of parallel waves, the mutation gate and the report (#1884; design 13.1 items 13, 20, 21): the generated workflows walked against a
# throwaway repo, a fake gh and a fake claude, plus the tools called directly.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
d="$(mktemp -d)"; trap 'rm -rf "$d"' EXIT
source "$here/scripts/test-common.sh"
ev() { jq -r "$1" "$run/events.jsonl"; }
secs() { local t0=$SECONDS; "$@"; echo $((SECONDS - t0)); }

# --- a wave of two independent steps: separate worktrees, merged in step order, a post-wave check
walk parallel true; ((rc == 0)) || fail "a wave of two steps finishes: $out $(tail -3 "$d/err")"
grep -q "NODE pair1 success" <<<"$out" && grep -q "NODE merge success" <<<"$out" && grep -q "NODE wave_check success" <<<"$out" || fail "the wave ran as a pair and was checked"
jq -e '.status == "DONE"' "$run/steps/S1/status.json" "$run/steps/S2/status.json" >/dev/null || fail "both steps are DONE"
[[ ! -d "$d/yukta-issue-7-S2" ]] || fail "the sibling worktree is removed after the merge"
git -C "$d/yukta-issue-7" log --format=%s | grep -q "merge S2 into the run branch" || fail "the second step is merged into the run branch"
[[ -f "$d/yukta-issue-7/core/src/main/java/A.java" && -f "$d/yukta-issue-7/core/src/main/java/C.java" ]] || fail "both steps' files are on the run branch"
# --- the second step starts after the first has warmed the cache: its pack comes after the first implementer event
first_impl="$(ev 'select(.role=="implementer" and .step_id=="S1") | .ts' | head -1)"; [[ -n "$first_impl" ]] || fail "S1 called the implementer"

# --- two parallel steps finish in less wall-clock time than in sequence (the gate takes 3 s: validation overlaps the sibling's work)
t_par="$(secs walk parallel "sleep 3")"; ((rc == 0)) || fail "timing: parallel run failed"
t_seq="$(secs walk chain "sleep 3")"; ((rc == 0)) || fail "timing: sequential run failed"
echo "MEASURED parallel=${t_par}s sequential=${t_seq}s (two steps, a 3 s gate each, fake claude)"
((t_par < t_seq)) || fail "two parallel steps are faster than in sequence: ${t_par}s against ${t_seq}s"

# --- 20. merged steps pass individually but fail the post-wave check: an escalation attributed to the later step
GATE_WAVE=false walk parallel true; grep -q "NODE wave_check failure" <<<"$out" || fail "20: the post-wave check fails: $out"
[[ "$(ev 'select(.role=="wave" and .escalated==1) | .step_id' | head -1)" == S2 ]] || fail "20: attributed to the later step"
jq -e '.status == "DONE"' "$run/steps/S1/status.json" "$run/steps/S2/status.json" >/dev/null || fail "20: each step passed individually"

# --- 13. a merge conflict is an escalation, never resolved silently
git -C "$d/yukta-issue-7" worktree prune
mkwave() { # two branches changing the same file differently
  rm -rf "$d/cw" "$d/cwt"; git -C "$repo" worktree prune; git -C "$repo" branch -D cw-a cw-b >/dev/null 2>&1 || true
  git -C "$repo" worktree add -q -b cw-a "$d/cwt" main; echo one >"$d/cwt/Shared.txt"; git -C "$d/cwt" add -A; git -C "$d/cwt" commit -q -m a
  git -C "$repo" worktree add -q -b cw-b "$d/cw" main; echo two >"$d/cw/Shared.txt"; git -C "$d/cw" add -A; git -C "$d/cw" commit -q -m b
  r="$d/cwrun"; rm -rf "$r"; mkdir -p "$r/spec" "$r/steps/S1" "$r/steps/S2"; cp "$run/project.json" "$run/routing.json" "$run/ticket.json" "$r/"; : >"$r/events.jsonl"
  echo '{"status":"DONE"}' >"$r/steps/S1/status.json"; echo '{"status":"DONE"}' >"$r/steps/S2/status.json"
  jq -n --arg b "$(git -C "$repo" rev-parse main)" '{base:$b,steps:["S1","S2"],worktrees:{S1:"x",S2:"x"},branch:"cw-b"}' >"$r/wave.json"
}
mkwave; "$here/ticketkit" waves merge "$r" --worktree "$d/cwt" >"$d/o" 2>&1 && fail "13: a merge conflict must fail"
jq -e '.step == "S2" and .trigger == "step_failed"' "$r/escalation.json" >/dev/null || fail "13: escalated to the later step: $(cat "$r/escalation.json" 2>/dev/null)"
[[ -z "$(git -C "$d/cwt" status --porcelain)" && "$(cat "$d/cwt/Shared.txt")" == one ]] || fail "13: the merge was aborted, the run branch is untouched"

# --- eligibility: disjoint files pair up; package.json and the lockfile run alone; max_parallel 1 serializes
mkspec() { r="$d/er"; rm -rf "$r"; mkdir -p "$r/spec" "$r/steps"; cp "$run/project.json" "$r/"; jq --argjson f "$1" --arg mp "${2:-2}" '.max_parallel = ($mp|tonumber)' "$r/project.json" >"$r/p" && mv "$r/p" "$r/project.json"
  jq -n --argjson f "$1" '{steps: [$f[] | {id: .id, depends_on: (.dep // []), write_files: .files}]}' >"$r/spec/spec.json"; }
wave() { "$here/ticketkit" waves next-wave "$r" | jq -r '.mode + " " + (.steps | join(","))'; }
mkspec '[{"id":"S1","files":["core/A.java"]},{"id":"S2","files":["core/C.java"]}]'; [[ "$(wave)" == "pair S1,S2" ]] || fail "disjoint ready steps pair up"
mkspec '[{"id":"S1","files":["core/A.java"]},{"id":"S2","files":["core/C.java"],"dep":["S1"]}]'; [[ "$(wave)" == "single S1" ]] || fail "a dependent step waits"
mkspec '[{"id":"S1","files":["ui/package.json"]},{"id":"S2","files":["core/C.java"]}]'; [[ "$(wave)" == "single S1" ]] || fail "31: a step changing ui/package.json runs alone"
mkspec '[{"id":"S1","files":["core/A.java"]},{"id":"S2","files":["ui/pnpm-lock.yaml"]}]'; [[ "$(wave)" == "single S1" ]] || fail "31: the lockfile runs alone"
mkspec '[{"id":"S1","files":["core/A.java"]},{"id":"S2","files":["core/C.java"]}]' 1; [[ "$(wave)" == "single S1" ]] || fail "max_parallel 1 serializes"
mkdir -p "$r/steps/S1"; echo '{"status":"DONE"}' >"$r/steps/S1/status.json"
[[ "$(wave)" == "single S2" ]] || fail "a DONE step is not run again"

# --- warm before fan-out: the sibling waits for the first step's first implementer call
rm -rf "$d/wr"; mkdir -p "$d/wr"; echo '{"steps":["S1","S2"]}' >"$d/wr/wave.json"; : >"$d/wr/events.jsonl"
TICKET_WARM_TIMEOUT=1 "$here/ticketkit" waves warm-wait "$d/wr" S2 | grep -q "never warmed" || fail "the sibling waits, then goes on after the time box"
echo '{"role": "implementer", "step_id": "S1"}' >>"$d/wr/events.jsonl"; [[ -z "$(TICKET_WARM_TIMEOUT=1 "$here/ticketkit" waves warm-wait "$d/wr" S2)" ]] || fail "a warm cache lets the sibling start"
[[ -z "$("$here/ticketkit" waves warm-wait "$d/wr" S1)" ]] || fail "the first step does not wait"

# --- the mutation gate: medium or high risk, never :boot, :plus:* or :test-support; survivors above the threshold go to the reviewer
cat >"$d/pit.sh" <<'P'
mkdir -p core/build/reports/pitest/x
printf '<mutations><mutation status="SURVIVED"/><mutation status="SURVIVED"/><mutation status="SURVIVED"/><mutation status="KILLED"/></mutations>' >core/build/reports/pitest/x/mutations.xml
P
export TICKET_MUTATION_CMD="bash $d/pit.sh"
FAKE_PROMPTS=1 TEST_ASK=approve walk high true; ((rc == 0)) || fail "mutation: a high risk run finishes: $out"
jq -e '.exceeds == true and .survivors == 3 and .total == 4 and .modules == ["core"]' "$run/review/mutation.json" >/dev/null || fail "mutation: survivors above the threshold: $(cat "$run/review/mutation.json")"
grep -q "<mutation_finding>" "$d/prompts.log" && grep -q "3 of 4 mutants survived" "$d/prompts.log" || fail "mutation: the reviewer gets the finding"
walk happy true; jq -e 'has("skipped")' "$run/review/mutation.json" >/dev/null || fail "mutation: skipped at low risk"
unset TICKET_MUTATION_CMD
rm -rf "$d/mr"; mkdir -p "$d/mr/review" "$d/mr/steps/S1"; cp "$run/project.json" "$run/ticket.json" "$d/mr/"; jq '.risk="high"' "$run/routing.json" >"$d/mr/routing.json"
git -C "$d/yukta-issue-7" rev-parse HEAD~1 >"$d/mr/steps/S1/base"; mkdir -p "$d/yukta-issue-7/boot/src/main/java"; echo 'class Boot {}' >"$d/yukta-issue-7/boot/src/main/java/Boot.java"; git -C "$d/yukta-issue-7" add -A; git -C "$d/yukta-issue-7" commit -q -m boot
echo "git diff --name-only >/dev/null" >"$d/nop.sh"; git -C "$d/yukta-issue-7" diff --name-only HEAD~1 HEAD | grep -q boot/ || fail "setup: a boot class changed"
git -C "$d/yukta-issue-7" log --format=%H -1 >"$d/mr/steps/S1/base.tmp"; git -C "$d/yukta-issue-7" rev-parse HEAD~1 >"$d/mr/steps/S1/base"
TICKET_MUTATION_CMD="exit 9" "$here/ticketkit" flow mutation "$d/mr" --worktree "$d/yukta-issue-7" >"$d/o"
jq -e 'has("skipped")' "$d/mr/review/mutation.json" >/dev/null || fail "mutation: :boot is never mutated"

# --- the report: 10 sample runs, cache hit ratio per role, prefix mismatches, BLOCKED rate
rm -f "$d/events.jsonl"; i=0
for m in happy happy happy happy happy parallel parallel twostep chain happy; do
  i=$((i + 1)); RUNID="7-2026010${i}T000000Z" walk "$m" true; ((rc == 0)) || fail "sample run $i ($m) failed: $out"
done
r="$("$here/ticketkit" report --events "$d/events.jsonl")"
echo "$r"
grep -q "ticket report: 10 runs" <<<"$r" || fail "report: 10 runs"
grep -qE "cache hit ratio by role: .*implementer 89%" <<<"$r" || fail "report: the cache hit ratio per role (900 read of 1010 input)"
grep -q "architect .*%" <<<"$r" && grep -q "cost share by role" <<<"$r" || fail "report: cost share by role"
grep -q "prefix_hash mismatches: none" <<<"$r" || fail "report: no mismatch in healthy runs: $r"
grep -qE "haiku/medium/standard: [0-9]+ steps, first try" <<<"$r" || fail "report: first-try pass rate by (model, effort, complexity)"
jq -e '.blocked_rate > 0' <("$here/ticketkit" report --events "$d/events.jsonl" --json) >/dev/null || fail "report: the BLOCKED rate counts the blocked step"
# --- 21. two calls in one run with different prefix_hash and no revision between them: flagged
cp "$d/events.jsonl" "$d/events2.jsonl"
printf '%s\n%s\n' '{"run_id":"bad-run","role":"pack","step_id":"S1","prefix_hash":"sha256:aaa"}' '{"run_id":"bad-run","role":"pack","step_id":"S2","prefix_hash":"sha256:bbb"}' >>"$d/events2.jsonl"
"$here/ticketkit" report --events "$d/events2.jsonl" | grep -q "prefix_hash mismatches: bad-run (2 prefixes, 0 revisions)" || fail "21: a prefix mismatch is flagged"
# the BLOCKED rate above 15% is called out
jq -nc '{run_id:"b",role:"decide",step_id:"S1",attempt:1,fail_class:"blocked_reported",model:"haiku",effort:"medium",complexity:"standard"}' >"$d/b.jsonl"
"$here/ticketkit" report --events "$d/b.jsonl" | grep -q "the steps are too small" || fail "BLOCKED above 15% says the steps are too small"
echo "OK test-waves"
