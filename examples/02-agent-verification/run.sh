#!/usr/bin/env bash
# Use case: an AI coding agent must pass build, tests, a patch guard and a coverage gate before
# "done" is recorded. A failed gate calls the fixer (the agent) and retries, at most 3 attempts.
# This script builds a throwaway git project per scenario and plays the agent with small scripts.
# Needs: git, and a server that allows `sh` and `echo` (yukta.process.allowed-commands=sh,echo).
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
for p in PATCH_GUARD OUTCOME_TERMINAL PROCESS_EXECUTOR SUB_WORKFLOW LOOP_PREDICATE; do require_plugin "$p"; done

SID=$(jq -r .sessionId session.json)
BASE=/tmp/yukta-example-agent-verification   # the paths in session.json point here
REPO=$BASE/repo
rm -rf "$BASE"; mkdir -p "$BASE"

# --- helpers (local to this example) -------------------------------------------------------
# require_process: SKIP (exit 3) when the server refuses to run commands
require_process() {
  local probe='{"sessionId":"example-uc-probe","name":"probe","description":"probe","initiator":"examples","projectPath":"/tmp","workflows":{"p":{"workflowId":"p","description":"p","nodes":[{"nodeId":"t","type":"CONSTANT_SOURCE","config":{}},{"nodeId":"e","type":"PROCESS_EXECUTOR","config":{"command":["echo","ok"]}},{"nodeId":"x","type":"CONSOLE_TERMINAL","config":{}}],"edges":[{"source":"t","target":"e"},{"source":"e","target":"x"}]}}}'
  local eid st i
  api POST /api/sessions "$probe" >/dev/null
  eid=$(api POST /api/workflow/start '{"sessionId":"example-uc-probe","workflowId":"p"}' | jq -r .data.executionId)
  for ((i = 0; i < 20; i++)); do
    st=$(api GET "/api/executions/$eid" 2>/dev/null | jq -r .data.status || true)
    [[ $st =~ $TERMINAL_STATUSES ]] && break; sleep 1
  done
  api DELETE /api/sessions/example-uc-probe >/dev/null 2>&1 || true
  if [[ $st != SUCCESS ]]; then
    echo "SKIP: the server does not run commands (set yukta.process.allowed-commands=sh,echo or YUKTA_PROCESS_ALLOW_ANY=true)"
    exit 3
  fi
}
start_run() {  # -> EID
  EID=$(api POST /api/workflow/start "$(jq -n --arg s "$SID" '{sessionId:$s,workflowId:"verify-change",payload:{}}')" | jq -r .data.executionId)
}
wait_done() {  # [SECONDS] -> STATUS, LOGS
  local i
  for ((i = 0; i < ${1:-90}; i++)); do
    STATUS=$(api GET "/api/executions/$EID" 2>/dev/null | jq -r .data.status || true)
    [[ $STATUS =~ $TERMINAL_STATUSES ]] && break
    sleep 1
  done
  LOGS=$(curl -s -m 15 "$YUKTA_URL/api/sessions/$SID/executions/$EID/logs" | sed -n 's/^data://p' || true)
}
outcome_of() {  # NODE: the outcome the parent terminal NODE reported, or nothing when it was not reached
  api GET "/api/executions/$EID" | jq -r --arg n "$1" '.data.tasks[] | select(.nodeId == $n and .metadata.reached == true) | .metadata.outcome'
}
# children: sets CHILDREN to the ids of the attempt runs started by this run, oldest first
children() {
  local c
  CHILDREN=()
  for c in $(api GET /api/executions | jq -r --arg s "$SID" '[.data[] | select(.sessionId == $s and .workflowId == "attempt")] | sort_by(.startTime) | .[].executionId'); do
    curl -s -m 10 "$YUKTA_URL/api/sessions/$SID/executions/$c/logs" | grep -qF "yukta.parentExecutionId=$EID" && CHILDREN+=("$c")
  done
}
ran() {  # CHILD NODE: true when NODE ran in that attempt (a node that was not taken is SKIPPED)
  api GET "/api/executions/$1" | jq -e --arg n "$2" '.data.tasks[] | select(.nodeId == $n and .status == "SUCCESS")' >/dev/null
}
reached() {  # CHILD NODE: true when the terminal NODE received a message in that attempt
  api GET "/api/executions/$1" | jq -e --arg n "$2" '.data.tasks[] | select(.nodeId == $n and .metadata.reached == true)' >/dev/null
}
check() {  # NAME CONDITION...: run the condition, fail the example if it is false
  local name=$1 neg=0; shift
  [[ $1 == "!" ]] && { neg=1; shift; }
  if "$@"; then (( neg == 0 )) || { echo "    FAIL $name: expected false: $*"; exit 1; }
  else (( neg == 1 )) || { echo "    FAIL $name: $*"; sed 's/^/      /' <<<"$LOGS"; exit 1; }; fi
}
# finish NAME PARENT_NODE OUTCOME N_ATTEMPTS: the run ended SUCCESS, the parent terminal reported OUTCOME, N attempts ran
finish() {
  local got
  got=$(outcome_of "$2"); children
  [[ $STATUS == SUCCESS && $got == "$3" && ${#CHILDREN[@]} == "$4" ]] || {
    echo "    FAIL $1: status=$STATUS, $2 outcome='$got' (want $3), attempts=${#CHILDREN[@]} (want $4)"; sed 's/^/      /' <<<"$LOGS"; exit 1; }
  echo "    PASS $1: $2 reached with outcome $3 after ${#CHILDREN[@]} attempt(s)"
}
not_in_logs() { grep -qF -- "$1" <<<"$LOGS" && { echo "    FAIL: unexpected '$1' in logs"; exit 1; } || true; }

# new_repo: a committed project whose gates pass. The agent's change stays uncommitted.
new_repo() {  # [COVERAGE]
  rm -rf "$REPO"; mkdir -p "$REPO/src/main/java" "$REPO/src/test/java"
  cat >"$REPO/build.sh" <<'B'
#!/bin/sh
[ -f src/main/java/App.java ] || { echo "build: App.java missing"; exit 1; }
echo "build: ok"
B
  cat >"$REPO/test.sh" <<'B'
#!/bin/sh
[ -f BROKEN ] && { echo "tests: 1 failed"; exit 1; }
echo "tests: $(cat src/test/java/*Test.java | grep -c '@Test') passed"
B
  cat >"$REPO/coverage.sh" <<'B'
#!/bin/sh
echo "{\"coverage\": $(cat coverage.txt)}"
B
  echo "class App { int v = 1; }" >"$REPO/src/main/java/App.java"
  printf '@Test void a() {}\n@Test void b() {}\n' >"$REPO/src/test/java/AppTest.java"
  echo "${1:-90}" >"$REPO/coverage.txt"
  git -C "$REPO" init -q; git -C "$REPO" add -A
  git -C "$REPO" -c user.name=example -c user.email=example@example.com commit -qm base
}
agent_edit() { echo "class App { int v = 2; }" >"$REPO/src/main/java/App.java"; }  # the agent's change

# Stand-in for the agent's fix step: repairs what the scenarios broke, or does nothing.
cat >"$BASE/fixer-repairs.sh" <<'F'
#!/bin/sh
cd "$1" && rm -f BROKEN && git checkout -q -- src/test && echo "fixer: repaired the change"
F
cat >"$BASE/fixer-noop.sh" <<'F'
#!/bin/sh
echo "fixer: could not repair the change"
F
use_fixer() { cp "$BASE/fixer-$1.sh" "$BASE/fixer.sh"; }
# --------------------------------------------------------------------------------------------

require_process
apply_session session.json

echo "--> 1: clean change passes every gate on the first attempt"
new_repo; agent_edit; use_fixer repairs
start_run; wait_done
finish clean done SUCCESS 1
check clean reached "${CHILDREN[0]}" ok
check clean grep -qF "DONE RECORDED" <<<"$LOGS"
check clean ! ran "${CHILDREN[0]}" fix

echo "--> 2: failing tests are repaired and the second attempt passes"
new_repo; agent_edit; touch "$REPO/BROKEN"; use_fixer repairs
start_run; wait_done
finish broken-then-fixed done SUCCESS 2
check test-failed ran "${CHILDREN[0]}" fix
check test-failed ! ran "${CHILDREN[0]}" patch-guard   # a red build never reaches the later gates
check second-attempt reached "${CHILDREN[1]}" ok

echo "--> 3: the agent deletes a test; the patch guard rejects it, the fixer restores it"
new_repo; agent_edit; rm "$REPO/src/test/java/AppTest.java"; use_fixer repairs
start_run; wait_done
finish deleted-test done SUCCESS 2
check guard-rejected ran "${CHILDREN[0]}" fix
check guard-rejected ! ran "${CHILDREN[0]}" coverage
check second-attempt reached "${CHILDREN[1]}" ok

echo "--> 4: the agent reports done without changing anything: guard rejects, hard stop"
new_repo; use_fixer noop
start_run; wait_done
finish no-change stopped ESCALATE 3
check no-change ! ran "${CHILDREN[2]}" coverage
check no-change ! grep -qF "DONE RECORDED" <<<"$LOGS"
check no-change grep -qF "HARD STOP" <<<"$LOGS"

echo "--> 5: coverage 55 (below 80) and nobody can fix it: 3 attempts, hard stop, no done"
new_repo 55; agent_edit; use_fixer noop
start_run; wait_done
finish low-coverage stopped ESCALATE 3
check low-coverage ran "${CHILDREN[2]}" coverage-gate
check low-coverage ! ran "${CHILDREN[2]}" verified
check low-coverage ! grep -qF "DONE RECORDED" <<<"$LOGS"
echo "PASS 02-agent-verification"
