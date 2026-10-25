#!/usr/bin/env bash
# Token and cost totals of Claude sessions, in the attribute names of the OpenTelemetry GenAI conventions
# (still in Development status): gen_ai.usage.input_tokens, gen_ai.usage.output_tokens,
# gen_ai.usage.cache_creation.input_tokens, gen_ai.usage.cache_read.input_tokens. Sourced, not executed.

# usage_of FILE: a `claude -p --output-format json` result -> one usage object (+ cost_usd, turns, duration_ms)
usage_of() {
  jq -c '{"gen_ai.usage.input_tokens": (.usage.input_tokens // 0),
          "gen_ai.usage.output_tokens": (.usage.output_tokens // 0),
          "gen_ai.usage.cache_creation.input_tokens": (.usage.cache_creation_input_tokens // 0),
          "gen_ai.usage.cache_read.input_tokens": (.usage.cache_read_input_tokens // 0),
          cost_usd: (.total_cost_usd // 0), turns: (.num_turns // 0), duration_ms: (.duration_ms // 0)}' "$1" 2>/dev/null \
    || echo '{"gen_ai.usage.input_tokens":0,"gen_ai.usage.output_tokens":0,"gen_ai.usage.cache_creation.input_tokens":0,"gen_ai.usage.cache_read.input_tokens":0,"cost_usd":0,"turns":0,"duration_ms":0}'
}

# usage_sub IMPLEMENT_JSON HEAL_DIR: the implementer record and every heal attempt record of one issue run, summed.
# Records are named by attempt, so a second run in the same worktree overwrites the first run's (yukta issue
# #1250); the sums then miss it.
usage_sub() {
  local files=()
  mapfile -t files < <(ls "$1" "$2"/*-[0-9]*.json 2>/dev/null)
  if ((${#files[@]} == 0)); then
    echo '{"gen_ai.usage.input_tokens":0,"gen_ai.usage.output_tokens":0,"gen_ai.usage.cache_creation.input_tokens":0,"gen_ai.usage.cache_read.input_tokens":0,"cost_usd":0,"attempts":0,"fixed":0}'
    return
  fi
  jq -cs 'map({i: (.usage.input_tokens // .usage.input // 0), o: (.usage.output_tokens // .usage.output // 0),
               w: (.usage.cache_creation_input_tokens // (.usage.cache_creation | numbers) // 0),
               r: (.usage.cache_read_input_tokens // .usage.cache_read // 0), c: (.cost // 0),
               f: ((.fixed // .done // false) | if . then 1 else 0 end)})
         | {"gen_ai.usage.input_tokens": (map(.i) | add), "gen_ai.usage.output_tokens": (map(.o) | add),
            "gen_ai.usage.cache_creation.input_tokens": (map(.w) | add), "gen_ai.usage.cache_read.input_tokens": (map(.r) | add),
            cost_usd: (map(.c) | add), attempts: length, fixed: (map(.f) | add)}' "${files[@]}"
}
