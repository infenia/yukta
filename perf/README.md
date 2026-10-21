# Performance tests (k6)

| Test | Pattern | Script |
|------|---------|--------|
| Load | Ramp to a peak rate (default 100 starts/s), hold, ramp down | `k6/load.js` |
| Soak | Constant rate (default 20 starts/s, 1 h) with JVM heap, thread and CPU samples | `k6/soak.js` |

Both scripts share `k6/lib.js` and register one session per workflow from `k6/workflows.js`, then repeatedly pick one in turn (round-robin),
`POST /api/workflow/start` and poll `GET /api/executions/{id}` until it ends. Select with
`-e WORKFLOWS=mapper,chain10`. Default: `trigger-terminal,mapper,filter,chain10`. Also available: `fanout` and
`process` (spawns `echo`, heavy). To cover another plugin, add an entry to `CATALOG`.

Metrics (each also tagged `wf:<name>`, with a threshold per workflow): `workflow_e2e` (start to end, p95 < 2 s),
`workflow_success` (> 99%), `workflow_rejected` (share of starts refused with HTTP 429; limit via
`-e MAX_REJECTED=0.05`, default 0.01), `workflow_lenient_done`.

The runs-never-finish bug (#331) is fixed. `LENIENT` still defaults to `true`, which counts a run as done once all
nodes except `CONSOLE_TERMINAL` ended; pass `-e LENIENT=false` to require the run itself to reach a final status.
Open problems found by these tests are in [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

## Run

```bash
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun   # prod: no DEBUG web logging
# offline/API only: add -x :ui:pnpmInstall -x :ui:pnpmBuild -x :ui:pnpmSetup -x :ui:nodeSetup

k6 run perf/k6/load.js                                  # ramp 1m, hold 3m, ramp down 1m @ 100 starts/s
k6 run -e RATE=200 -e HOLD=10m perf/k6/load.js
k6 run perf/k6/soak.js                                  # 20 starts/s for 1h
k6 run -e DURATION=8h -e RATE=10 perf/k6/soak.js
```

Env: `BASE_URL` (default `http://localhost:8080`), `API_KEY` (if `yukta.api-key` is set),
`RATE`, `RAMP`, `HOLD` (load), `RATE`, `DURATION` (soak).

## Reading the soak

`jvm_memory_used`, `jvm_threads_live` and `process_cpu_usage` are sampled every 30s from
`/actuator/metrics`. Use `--out json=soak.json` to see the trend over time: heap and thread
count should plateau; steady growth means a leak. Bound server memory with
`yukta.tracker.cleanup-ttl`, `yukta.execution-log.retained-runs`; throttle with
`yukta.execution.max-concurrent` / `queue-capacity` (HTTP 429 shows up as `workflow_rejected`).
