# The analyzer's stage 1: facts and rule findings of one run, no model involved.
#
# Input: {draw, outcome, attempts, implement, modules, files, traces}
#   draw, outcome   the run's ledger records
#   attempts        the heal records of the issue worktree (stage, attempt, mode, fixed, cost, files, summary, usage)
#   implement       the implementer's (or the solo session's) record, or null
#   modules         the modules the issue text names (issue-size.sh module_names), the fallback for the issue's modules
#   files           every file the run changed (committed on the branch or still in the worktree)
#   traces          eval/cache_trace.py --json of the worktree: {sessions: [{id, turns, write, read, tools, rewrites}]}
#   nav             method C: the issue's nav.json (backend, ok, index_seconds, attempts), or null
# Output: {facts, findings}; every finding is {rule, evidence} and rule names a row of analyzer-known.json.

def under($mods): . as $f | any($mods[]; . as $m | $f | startswith($m + "/"));
def r2: . * 100 | round / 100;
def cache: (.usage // {}) | ((.cache_creation_input_tokens // .cache_creation // 0) + (.cache_read_input_tokens // .cache_read // 0));

# the issue's modules: where the implementer's own change is (the files on the branch no fixer touched), the modules
# its text names only when that is empty (a text names modules as examples too, so it over-counts)
(.files // []) as $files
| ([(.attempts // [])[] | (.files // [])[]] | unique) as $fixed_files
| ($files - $fixed_files) as $own_files
| (if ($own_files | length) > 0 then ($own_files | map(split("/")[0]) | unique) else (.modules // []) end) as $mods
| ((.attempts // []) | map(. + {in_module: ((.files // []) | any(under($mods))), changed: (((.files // []) | length) > 0)})) as $att
| ($att | map(select(.fixed | not) | select(.changed | not))) as $noop
| ($att | map(select(.changed and (.in_module | not)))) as $outside
| (($att | map(.cost // 0) | add) // 0) as $fixer_cost
| ((.traces.sessions // [])) as $sessions
| ($sessions | map(.rewrites | length) | add // 0) as $rewrite_turns
| ($sessions | map(.write + .read) | add // 0) as $transcript_cache
| ((($att | map(cache) | add) // 0) + ((.implement // {}) | cache)) as $record_cache
| ((.outcome.result // "") | capture("status=(?<s>[A-Z]+)")? // {s: ""}) as $st
| ((.outcome.result // "") | capture("healed=(?<h>[^ ]+)")? // {h: null}) as $hl
| {
    run: .draw.id, issue: .draw.issue, method: .draw.method, size: .draw.size, loop_sha: .draw.loop_sha,
    loop_changing: .draw.loop_changing,
    status: $st.s, delivered: ($st.s == "SUCCESS"), healed_field: $hl.h,
    cost_usd: (.outcome.cost_usd // 0), wall_s: (.outcome.wall_s // 0),
    main_cost_usd: (.outcome.main.cost_usd // 0), sub_cost_usd: (.outcome.sub.cost_usd // 0),
    modules: $mods, modules_from: (if ($own_files | length) > 0 then "the implementer's own files" else "the issue text" end), text_modules: (.modules // []),
    files_changed: $files,
    attempts: ($att | map({stage, attempt, mode, fixed, cost: ((.cost // 0) | r2), files: (.files // []), in_module,
                          summary: ((.summary // "")[0:140])})),
    fixer_attempts: ($att | length), fixer_cost_usd: ($fixer_cost | r2),
    noop_attempts: ($noop | length), noop_cost_usd: (($noop | map(.cost // 0) | add // 0) | r2),
    outside_attempts: ($outside | length), outside_files: ($outside | map(.files[]) | unique | .[0:8]),
    sessions: ($sessions | length), turns: ($sessions | map(.turns) | add // 0),
    rewrite_turns: $rewrite_turns, rewrites: ($sessions | map(.rewrites[]) | .[0:4]),
    tool_use: ($sessions | map(.tools | to_entries) | flatten | group_by(.key) | map({(.[0].key): (map(.value) | add)}) | add // {}),
    record_cache_tokens: $record_cache, transcript_cache_tokens: $transcript_cache,
    transcript_to_record: (if $record_cache > 0 then ($transcript_cache / $record_cache | r2) else null end),
    nav: (if .nav then {backend: .nav.backend, ok: (.nav.ok // false), index_seconds: (.nav.index_seconds // 0), version: (.nav.version // ""),
                        skipped: ((.nav.attempts // []) | map(select(.ok | not) | "\(.backend): \(.reason)"))} else null end),
    nav_calls: ($sessions | map(.tools | to_entries) | flatten
                | map(select(.key | startswith("mcp__codebase-memory-mcp__") or startswith("mcp__intellij-index__") or . == "Bash:codegraph") | .value) | add // 0)
  } as $f
| {
    facts: $f,
    findings: ([
      (if $f.outside_attempts > 0 and ($mods | length) > 0 then
        {rule: "fixer-outside-module",
         evidence: "\($f.outside_attempts) fixer attempt(s) changed files outside the module(s) of the implementer's change (\($mods | join(", "))): \($f.outside_files | join(", ")). The issue run is #\($f.issue), method \($f.method)."} else empty end),
      (if $f.noop_attempts > 0 then
        {rule: "noop-fixers",
         evidence: "\($f.noop_attempts) of \($f.fixer_attempts) fixer attempt(s) changed nothing and cost $\($f.noop_cost_usd) of $\($f.fixer_cost_usd) fixer spend (run cost $\($f.cost_usd | r2))."} else empty end),
      (if $f.sessions > 1 and $f.method != "A" then
        {rule: "restart-overwrites-records",
         evidence: "\($f.sessions) implementer sessions in one worktree: the run was started again, and the records of the earlier run were overwritten."} else empty end),
      (if ($f.transcript_to_record // 0) > 1.1 then
        {rule: "records-undercount",
         evidence: "the transcripts hold \($f.transcript_cache_tokens) cache tokens, \($f.transcript_to_record)x the \($f.record_cache_tokens) on record."} else empty end),
      (if $f.method == "C" and (($f.nav.ok // false) | not) then
        {rule: "nav-backend-none",
         evidence: "method C ran without a navigation backend: \(($f.nav.skipped // []) | join("; "))."} else empty end),
      (if $f.method == "C" and ($f.nav.ok // false) and $f.nav_calls == 0 then
        {rule: "nav-unused",
         evidence: "the \($f.nav.backend) index took \($f.nav.index_seconds)s to build and the implementer made no navigation call (\($f.turns) turns, tools \($f.tool_use | tojson))."} else empty end),
      (if $f.healed_field == "-" and ($att | any(.fixed)) then
        {rule: "healed-field-mismatch",
         evidence: "the RESULT line says healed=- although \($att | map(select(.fixed)) | length) fixer attempt(s) reported a fix (\($att | map(select(.fixed) | "\(.stage):\(.mode)") | join(", ")))."} else empty end)
    ])
  }
