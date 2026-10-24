# Performance baseline

Measured on 2026-10-24 with `perf/run.sh` (jar, `eclipse-temurin:25-jre`, 2 vCPU pinned to cores 0-1, 8 GB, no
swap, `-Dyukta.rate-limit-per-minute=0`), k6 v2.3.0 on other cores. Commit `af0f9f24` (main `38162b12` plus the
`perf/` harness fixes), clean tree; `meta.json` of each report names this commit. Workflows: the default set
(`trigger-terminal,mapper,filter,chain10,fanout`), round-robin.

| Test | Rate / duration | p50 | p95 | p99 | Success | Peak RSS | Stuck | Events lost |
|---|---|---|---|---|---|---|---|---|
| perf #1 | 10/s, 2 m | 59 ms | 72 ms | 114 ms | 100% (1201/1201) | 400 MiB | 0 | 0 |
| perf #2 | 10/s, 2 m | 58 ms | 73 ms | 115 ms | 100% (1201/1201) | 403 MiB | 0 | 0 |
| perf #3 | 10/s, 2 m | 57 ms | 79 ms | 113 ms | 100% (1201/1201) | 372 MiB | 0 | 0 |
| load | 200/s, 10 m hold | 53 ms | 62 ms | 773 ms | **95.52%** (118,539/124,087) | 1878 MiB | **9** | 0 |
| soak | 10/s, 2 h | not valid | | | | | | |

## Run-to-run noise (`perf`, three runs)

Overall `workflow_e2e` p95: 72, 73, 79 ms (spread 7 ms, about 10%). Per workflow, p95 spread: `chain10` 109-111 ms
(2%), `trigger-terminal` 67-69 ms (3%), `mapper` 70-71 ms (1%), `filter` 68-73 ms (7%), `fanout` 67-76 ms (13%).
A p95 regression threshold below about 15% would raise false alarms on the fast workflows; 20% is the lowest safe
value. `p50` is stable to 2 ms.

## Findings

- **load**: from about minute 10 the status of finished runs is not seen as final within 5 s (all runs in the last
  two minutes), 9 runs are still `RUNNING` after the grace period, 8,062 iterations were dropped. See #1145.
- **soak: no valid result.** The first attempt ran cleanly for about 33 minutes (about 18,000 starts, all
  accepted; RSS 340 MB at the start, 380-410 MB later, flat) and then every request returned 401: the server
  container was replaced by another instance that was not started by this run, so the remainder measures nothing.
  The 2 h soak is still to be run on a machine where nothing else uses the `yukta-perf` compose project and port
  8080.
- **Harness fixes found on the way** (in this change): `run.sh` hardcoded `boot-0.0.1-SNAPSHOT.jar`, so every run
  died with `Invalid or corrupt jarfile`; and the default per-key API limit (1200 requests/min) refused 34% of the
  starts at 10 starts/s, so the benchmark server now sets `yukta.rate-limit-per-minute=0`. A native binary ignores
  `JAVA_OPTS`, so the limit still applies there.
