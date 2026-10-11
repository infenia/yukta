# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Performance tests (k6)

Both scripts register one session per workflow from `k6/workflows.js`, then repeatedly pick one at random,
`POST /api/workflow/start` and poll `GET /api/executions/{id}` until it ends. Select with
`-e WORKFLOWS=mapper,chain10`. Default: `trigger-terminal,mapper,filter,chain10`. Also available: `fanout`
(hangs, see issues) and `process` (spawns `echo`, heavy). To cover another plugin, add an entry to `CATALOG`.

Metrics (each also tagged `wf:<name>`, with a threshold per workflow): `workflow_e2e` (start to end),
`workflow_success`, `workflow_rejected` (HTTP 429, not a failure), `workflow_lenient_done`.

Runs currently never reach a final status because of a server bug, so `LENIENT=true` (default) counts a run as
done when all nodes except `CONSOLE_TERMINAL` ended. Use `-e LENIENT=false` once fixed. Open problems found by
these tests are in [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

## Run

```bash
SPRING_PROFILES_ACTIVE=prod YUKTA_SERVER_ADDRESS=0.0.0.0 ./gradlew bootRun   # prod: no DEBUG web logging
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
