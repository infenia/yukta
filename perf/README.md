# Performance tests (k6)

Three tests share one engine (`k6/lib.js`): `perf.js` (baseline latency at a fixed rate, for comparing commits),
`load.js` (ramp to a peak rate) and `soak.js` (steady rate for hours, with JVM trend). Each registers one session
per workflow from `k6/workflows.js`, then picks one in turn (round-robin), `POST /api/workflow/start` and polls
`GET /api/executions/{id}` until it ends. Select with `-e WORKFLOWS=mapper,chain10`. Default:
`trigger-terminal,mapper,filter,chain10,fanout`. Also available: `process` (spawns `echo`, heavy). To cover
another plugin, add an entry to `CATALOG`.

Metrics (each also tagged `wf:<name>`, with a threshold per workflow): `workflow_e2e` (start to end),
`workflow_success`, `workflow_rejected` (share of starts refused with HTTP 429; limit via `-e MAX_REJECTED=0.05`,
default 0.01), `poll_not_found` (status read 404 right after the 202, see KNOWN-ISSUES #5).

Two checks catch what a pass on latency hides, both must be 0:
- `stuck_runs`: after the test, runs of the test sessions whose own status is still `RUNNING` after `-e GRACE=30`
  seconds. They are printed as `STUCK <workflow>: <n> e.g. <ids>`.
- `tracker_events_lost`: growth of the server metric `yukta.tracker.dropped{type=state}`.

`LENIENT` (default false) counts a run as done once all nodes except `CONSOLE_TERMINAL` ended; it hides runs whose
own status never turns final, so leave it off. Open problems found by these tests are in
[KNOWN-ISSUES.md](KNOWN-ISSUES.md).

## Benchmark with fixed resources (recommended)

`perf/run.sh <perf|load|soak> [k6 args]` runs the server from `boot/build/libs/boot-0.0.1-SNAPSHOT.jar` in a
container with fixed CPU and memory, runs the test from other cores, and writes a report.

```bash
./gradlew :boot:bootJar                         # the jar it runs
perf/run.sh perf                                # 2 vCPU, 8 GB (defaults), 10 starts/s for 2m
DURATION=5m RATE=20 perf/run.sh perf
RATE=200 HOLD=10m perf/run.sh load
DURATION=8h RATE=10 perf/run.sh soak
CPUS=4 CPUSET=0-3 MEMORY=16g perf/run.sh load   # another size
KEEP=1 perf/run.sh load                         # leave the server up to inspect stuck runs (prints the API key)
```

Env: `CPUS` (2), `CPUSET` (0,1; the cores the server is pinned to), `MEMORY` (8g, no swap), `K6_CPUSET` (2-15;
keep it off the server cores), `JAVA_OPTS` (`-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`), `IMAGE`
(`eclipse-temurin:25-jre`). `RATE`, `RAMP`, `HOLD`, `DURATION`, `WORKFLOWS`, `GRACE` are passed to k6.

Report in `perf/reports/<perf|load|soak>/<UTC time>-<commit>/` (git-ignored): `meta.json` (commit, dirty flag,
resources, options, tool versions), `summary.json` and `k6.log` (results), `dashboard.html` (k6 web dashboard),
`metrics.csv.gz` (raw samples), `container-stats.csv` (server CPU and memory every 5 s), `server.log` and
`diagnostics.txt` (counts of lost tracker events and errors). The exit code is k6's, so a failed threshold fails
the script.

## Without a container

```bash
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun   # prod: no DEBUG web logging
# offline/API only: add -x :ui:pnpmInstall -x :ui:pnpmBuild -x :ui:pnpmSetup -x :ui:nodeSetup

k6 run perf/k6/load.js                                  # ramp 1m, hold 3m, ramp down 1m @ 100 starts/s
k6 run -e RATE=200 -e HOLD=10m perf/k6/load.js
k6 run perf/k6/soak.js                                  # 20 starts/s for 1h
k6 run -e DURATION=8h -e RATE=10 perf/k6/soak.js
```

Env: `BASE_URL` (default `http://localhost:8080`), `API_KEY` (if `yukta.api-key` is set),
`RATE`, `RAMP`, `HOLD` (load), `RATE`, `DURATION` (perf, soak).

## Reading the soak

`jvm_memory_used`, `jvm_threads_live` and `process_cpu_usage` are sampled every 30s from
`/actuator/metrics`. Use `--out json=soak.json` to see the trend over time: heap and thread
count should plateau; steady growth means a leak. Bound server memory with
`yukta.tracker.cleanup-ttl`, `yukta.execution-log.retained-runs`; throttle with
`yukta.execution.max-concurrent` / `queue-capacity` (HTTP 429 shows up as `workflow_rejected`).
