# Measurements

## Parallel steps against sequential steps (#1884)

Measured once on 2026-11-03 with `test/waves.test.ts` (the generated `ticket` workflow walked offline, a fake Claude, a 3 s step gate): two steps on disjoint files.

| Run | Wall clock |
|---|---|
| two steps in a wave (parallel worktrees, merged, post-wave check) | 12 s |
| the same two steps in sequence (the second depends on the first) | 18 s |

What this shows: the wave overlaps the first step's validation with the second step's implementation and validation, and the saving is the overlap. It does not include real model latency or a real Gradle gate; with a real gate of minutes the saving is larger, and the second step still waits for the first step's first implementer call (cache warm-up, design 11.1). Measure it again on a real ticket once real runs are allowed.

## Sample report (10 offline runs, fake Claude)

`ticketkit report --events <ticket-events.jsonl>` on ten runs (five single-step, two parallel, one with a blocked step, one chain, one more single): first try 93% for haiku/medium/standard, cache hit ratio 89% per role (the fake reports 900 cache-read tokens in 1010), no `prefix_hash` mismatch, BLOCKED rate 7%. The numbers are those of the fake, not of a real model.
