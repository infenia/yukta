#!/usr/bin/env bash
# Online test of the check and heal workflows against a fake claude and a fake System One (no API cost, about a minute).
# Needs the dev server (yk up). The project is a throwaway git repo whose stages are `test -f <stage>.ok`; the fake claude
# creates that file (or not) as the plan says, and logs when each call started and ended.
#
#   two stages        both are healed, one fixer after the other (no overlap)
#   no change         an attempt that changed nothing ends the ladder (sonnet is not called)
#   escalation        a change that does not fix it escalates to the next rung
#   single attempt    a stage in heal.json singleAttempt (reuse) gets one attempt even when it changed something
#   verify            a verify that fails goes back to heal
#   triage            a "hard" failure starts at sonnet/low (fake Ollama)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$here/scripts/_lib.sh"
use_dev_key
need jq
need python3
ensure_server
cli="$(find_cli)"
fail() { echo "FAIL $*" >&2; exit 1; }

dir="$(mktemp -d)"
trap 'kill ${ollama_pid:-0} 2>/dev/null || true; rm -rf "$dir"' EXIT
proj="$dir/proj" healdir="$dir/heal"
mkdir -p "$proj"
git -C "$proj" init -q
git -C "$proj" -c user.email=t@t -c user.name=t commit -q --allow-empty -m init

cat >"$dir/claude" <<'SH'
#!/usr/bin/env bash
# fake claude: the plan file lists "<stage> <model> <fix|wrong|nochange>" lines; a call not in the plan is a nochange
here="$(cd "$(dirname "$0")" && pwd)"
prompt="" model=""
while (($#)); do case "$1" in -p) prompt="$2"; shift 2 ;; --model) model="$2"; shift 2 ;; *) shift ;; esac; done
stage="$(grep -o 'Stage "[a-z_]*"' <<<"$prompt" | head -1 | cut -d'"' -f2)"
action="$(awk -v s="$stage" -v m="$model" '$1 == s && $2 == m { print $3 }' "$here/plan")"
printf '%s\n---\n' "$prompt" >>"$here/prompt.log"
echo "start $(date +%s.%N) $stage $model ${action:-nochange}" >>"$here/claude.log"
sleep 1
fixed=false
case "${action:-nochange}" in
  fix) touch "$stage.ok"; fixed=true ;;
  wrong) echo wrong >>wrong.txt; fixed=true ;;
esac
echo "end $(date +%s.%N) $stage $model" >>"$here/claude.log"
jq -nc --argjson f "$fixed" '{is_error: false, subtype: "success", total_cost_usd: 0.001,
  usage: {input_tokens: 1, output_tokens: 1}, structured_output: {fixed: $f, summary: "fake"}}'
SH
chmod +x "$dir/claude"

# scenario NAME PLAN-LINES... : sets $result to the RESULT line of a check of the project. The environment variables CMD_<stage>
# (a stage command; compile, tests, reuse), VERIFY (the verify command) and TRIAGE_URL choose the run.
scenario() {
  local name="$1" session="test-heal-$$" names cmds exec_id status
  shift
  git -C "$proj" clean -fdq
  rm -rf "$healdir" "$dir/claude.log"
  mkdir -p "$healdir"
  printf '%s\n' "$@" >"$dir/plan"
  names="$(jq -c '[.workflows.check.nodes[] | select(.type == "SUB_WORKFLOW" and .config.workflowId == "tool" and .nodeId != "verify") | .nodeId]' "$here/session.json")"
  cmds="$(jq -nc --argjson n "$names" --arg c "${CMD_compile:-}" --arg t "${CMD_tests:-}" --arg r "${CMD_reuse:-}" \
    '($n | map({(.): ""}) | add) + {compile: $c, tests: $t, reuse: $r}')"
  render_session "$here/session.json" "$proj" | jq --arg s "$session" '.sessionId=$s | .name=$s' >"$dir/session.json"
  "$cli" session apply "$dir/session.json" >"$dir/apply.out" 2>&1 || { cat "$dir/apply.out" >&2; fail "$name: session apply"; }
  exec_id="$("$cli" -o json workflow start "$session" check --payload "$(jq -nc --arg p "$proj" --arg d "$healdir" --arg bin "$dir/claude" \
    --arg rules "$here/fixer-rules.md" --arg verify "${VERIFY:-true}" --arg budget "${BUDGET_CHECK:--}" --arg fast "${FAST:-}" --arg mark "$healdir/fast.ok" --arg triage "${TRIAGE_URL:+on}" --arg turl "${TRIAGE_URL:-http://127.0.0.1:1}" \
    --argjson c "$cmds" --argjson n "$names" '
    ($n | map({(.): "-"}) | add) as $dash
    | {project: $p, timeout: 120, healDir: $d, events: "-", modules: "-", models: "-", heal: "on", triage: (if $triage == "on" then "on" else "off" end),
       rules: $rules, claudeBin: $bin, budgetCheck: $budget, triageModel: "fake", triageUrl: $turl, verifyCommand: $verify, cmd: $c, auto: $dash, fmt: $dash,
       fast: (if $fast == "" then "" else "\($fast) && touch \($mark)" end),
       chain: (if $fast == "" then $c else ($c | map_values(if . == "" then . else "test -f \($mark) || \(.)" end)) end)}')" \
    | jq -r '.data.executionId // .executionId')"
  [[ -n "$exec_id" && "$exec_id" != null ]] || fail "$name: could not start"
  for ((i = 0; i < 240; i++)); do
    status="$("$cli" -o json workflow status "$session" "$exec_id" | jq -r '(.data // .).status')"
    [[ "$status" =~ ^(RUNNING|QUEUED|PAUSED)$ ]] || break
    sleep 1
  done
  result="$(YUKTA_HEAL_RECORDS="$healdir" print_result "$session" "$exec_id" 2>/dev/null | grep '^RESULT' || true)"
  echo "     $name: $result"
}
calls() { grep -c '^start' "$dir/claude.log" 2>/dev/null || echo 0; }
expect() { [[ "$result" == *"$1"* ]] || fail "$name expected '$1' in: $result"; }

name="two stages"
CMD_compile='test -f compile.ok' CMD_tests='test -f tests.ok' VERIFY='test -f compile.ok && test -f tests.ok' \
  scenario "$name" "compile haiku fix" "tests haiku fix"
expect "status=SUCCESS"; expect "compile:haiku/high"; expect "tests:haiku/high"
# no two fixers at once: every call starts after the previous one ended
awk '$1 == "start" { if (end != "" && $2 < end) bad = 1 } $1 == "end" { end = $2 } END { exit bad }' "$dir/claude.log" || fail "$name: two fixers overlapped"

name="no change"
CMD_compile='test -f compile.ok' scenario "$name" "compile haiku nochange" "compile sonnet fix"
expect "status=FAILED"; expect "failed_node=compile"
[[ "$(calls)" == 1 ]] || fail "$name: expected 1 claude call, got $(calls)"

name="escalation"
CMD_compile='test -f compile.ok' scenario "$name" "compile haiku wrong" "compile sonnet fix"
expect "status=SUCCESS"; expect "compile:sonnet/low"
[[ "$(calls)" == 2 ]] || fail "$name: expected 2 claude calls, got $(calls)"

name="single attempt"
CMD_reuse='test -f reuse.ok' scenario "$name" "reuse haiku wrong" "reuse sonnet fix"
expect "status=FAILED"; expect "failed_node=reuse"
[[ "$(calls)" == 1 ]] || fail "$name: expected 1 claude call, got $(calls)"

name="check budget"
# the fake claude costs $0.001 a call: a cap of $0.0005 is spent after the first rung, so the second never starts
BUDGET_CHECK=0.0005 CMD_compile='test -f compile.ok' scenario "$name" "compile haiku nochange" "compile sonnet fix"
expect "status=FAILED"; expect "failed_node=compile"
[[ "$(calls)" == 1 ]] || fail "$name: expected 1 claude call, got $(calls)"

name="excerpts"
mkdir -p "$proj/src" && seq 1 40 | sed 's/^/line /' >"$proj/src/A.java" && git -C "$proj" add src/A.java
CMD_compile='echo "src/A.java:20: error: boom"; test -f compile.ok' scenario "$name" "compile haiku fix"
expect "status=SUCCESS"
grep -q 'line 20' "$dir/prompt.log" 2>/dev/null || fail "$name: no excerpt in the prompt"

name="fast path pass"
# the fast command passes: the stage commands of the chain are no-ops, so a stage that would fail is never run
FAST='true' CMD_compile='false' scenario "$name" "compile haiku fix"
expect "status=SUCCESS"; expect "healed=-"
[[ "$(calls)" == 0 ]] || fail "$name: expected no claude call, got $(calls)"

name="fast path fail"
# the fast command fails: the chain runs as ever, finds the failing stage and heals it
FAST='false' CMD_compile='test -f compile.ok' scenario "$name" "compile haiku fix"
expect "status=SUCCESS"; expect "compile:haiku/high"

name="verify"
CMD_compile='test -f compile.ok' VERIFY='test -f compile.ok && test -f verify.ok' scenario "$name" "compile haiku fix" "verify haiku fix"
expect "status=SUCCESS"; expect "verify:haiku/high"

name="triage"
python3 -I "$here/scripts/fake-ollama.py" "$dir/port" &
ollama_pid=$!
for ((i = 0; i < 20; i++)); do [[ -s "$dir/port" ]] && break; sleep 0.25; done
CMD_compile='echo error: compile failed difficulty: hard; test -f compile.ok' TRIAGE_URL="http://127.0.0.1:$(cat "$dir/port")" \
  scenario "$name" "compile haiku fix" "compile sonnet fix"
expect "status=SUCCESS"; expect "compile:sonnet/low"
grep -q ' haiku ' "$dir/claude.log" && fail "$name: haiku was called for a hard failure"

name="reuse auto fix"
if command -v reuse >/dev/null; then
  r="$dir/reuse-proj"; mkdir -p "$r/LICENSES"
  cp "$here/../../REUSE.toml" "$r/"; cp "$here/../../LICENSES/Apache-2.0.txt" "$r/LICENSES/"
  git -C "$r" init -q; echo 'class A {}' >"$r/A.java"; echo '{}' >"$r/b.json"; git -C "$r" add -A
  (cd "$r" && reuse lint -q >/dev/null 2>&1) && fail "$name: the fixture is compliant before the fix"
  (cd "$r" && "$here/scripts/fix-reuse.sh" >/dev/null) || fail "$name: fix-reuse.sh failed"
  (cd "$r" && reuse lint -q >/dev/null 2>&1) || fail "$name: still not compliant after the fix"
else
  echo "skip $name: reuse is not installed"
fi

echo "ok   heal"
