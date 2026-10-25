#!/usr/bin/env bash
# A fake `claude` for selftest.sh: prints usage JSON like `claude -p --output-format json`, and for the
# orchestrator prompt also creates the issue worktree with one implementer and two fixer records.
prompt="$2"
if grep -q 'you are the orchestrator' <<<"$prompt"; then
  n="$(grep -o 'Issue #[0-9]*' <<<"$prompt" | head -1 | tr -dc 0-9)"
  git worktree add -q "../yukta-issue-$n" -b "issue/$n" origin/main
  mkdir -p ".yukta-dev/issue-$n" "../yukta-issue-$n/.yukta-dev/heal/check"
  # method C: the issue run indexes the worktree
  echo '{"backend":"cbm","ok":true,"index_seconds":13,"version":"9.9.9"}' >".yukta-dev/issue-$n/nav.json"
  echo '{"cost":0.9,"done":true,"summary":"x","usage":{"input_tokens":10,"cache_creation_input_tokens":5000,"cache_read_input_tokens":30000,"output_tokens":2000}}' >".yukta-dev/issue-$n/implement.json"
  echo '{"stage":"tests","mode":"sonnet","effort":"medium","fixed":false,"cost":0.1,"files":[],"usage":{"input":5,"cache_creation":0,"cache_read":9000,"output":300}}' >"../yukta-issue-$n/.yukta-dev/heal/check/tests-1.json"
  echo '{"stage":"tests","mode":"sonnet","effort":"high","fixed":true,"cost":0.2,"files":["core/X.java"],"usage":{"input":5,"cache_creation":100,"cache_read":9000,"output":300}}' >"../yukta-issue-$n/.yukta-dev/heal/check/tests-2.json"
  echo '{"result":"RESULT status=SUCCESS","total_cost_usd":0.2,"num_turns":4,"duration_ms":1000,"usage":{"input_tokens":6,"cache_creation_input_tokens":3000,"cache_read_input_tokens":8000,"output_tokens":400}}'
else
  echo '{"result":"done","total_cost_usd":2.5,"num_turns":30,"duration_ms":90000,"usage":{"input_tokens":50,"cache_creation_input_tokens":40000,"cache_read_input_tokens":600000,"output_tokens":9000}}'
fi
