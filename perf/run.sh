#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Run a k6 test against Yukta in a container with fixed resources and keep a report.
#   perf/run.sh <perf|load|soak|inputs|notifications> [extra k6 args]
# Env: PERSISTENCE=memory|postgres (postgres: a PostgreSQL container, and a binary of the Plus flavour) PORT=9858 (the host port; a run on another port has its own compose project) CPUS=2 CPUSET=0,1 MEMORY=8g K6_CPUSET=2-15 (default: all cores after the server's; unpinned below 3 cores) IMAGE=... KEEP=1 (leave server up)
#      Always runs the native binary: it is built first (release/build-server.sh; NATIVE_QUICK=1, NATIVE_HEAP=24g).
#      BINARY=dist/linux-amd64/yukta skips that build and runs a binary built earlier (CI; image debian:12-slim)
#      plus the k6 -e variables, e.g. RATE=50 WORKFLOWS=mapper DURATION=10m
set -euo pipefail

type=${1:?usage: run.sh <perf|load|soak|inputs|notifications> [k6 args]}; shift
[[ $type =~ ^(perf|load|soak|inputs|notifications)$ ]] || { echo "unknown test '$type'" >&2; exit 2; }
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

export PORT=${PORT:-9858} CPUS=${CPUS:-2} CPUSET=${CPUSET:-0,1} MEMORY=${MEMORY:-8g}
cores=$(nproc)
[[ -n ${K6_CPUSET+x} || $cores -le 2 ]] || K6_CPUSET=2-$((cores - 1))   # k6 off the server cores; unpinned on small hosts
pin=(); [[ -z ${K6_CPUSET:-} ]] || pin=(taskset -c "$K6_CPUSET")
export JAVA_OPTS=${JAVA_OPTS:--XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Dyukta.rate-limit-per-minute=0}
export API_KEY=${API_KEY:-$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')}
# The server under test is always the native binary. With BINARY set (CI builds it in an earlier step or job and
# shares it between runs) that file is used as is; without it the binary is built here, before the run.
if [[ -z ${BINARY:-} ]]; then
  release/build-server.sh
  BINARY=dist/linux-$([[ $(uname -m) == @(aarch64|arm64) ]] && echo arm64 || echo amd64)/yukta
fi
[[ -f $BINARY && -x $BINARY ]] || { echo "BINARY $BINARY is not an executable file" >&2; exit 2; }
# absolute: compose reads a relative path without a leading ./ as the name of a volume, not a file
JAR=$(realpath "$BINARY"); export JAR   # compose mounts it at /app/yukta.jar
export RUN_CMD="/app/yukta.jar -Dyukta.rate-limit-per-minute=0" IMAGE=${IMAGE:-debian:12-slim}
# PERSISTENCE=postgres: the binary must be the Plus flavour (YUKTA_BUILD_FLAGS="-Pyukta.persistence=postgres
# -Pyukta.workers=true" release/build-server.sh). A PostgreSQL container joins the server's project and the
# server runs on the postgres profile; the same workloads then show what the database costs.
PERSISTENCE=${PERSISTENCE:-memory}
case $PERSISTENCE in
  memory) ;;
  postgres) export COMPOSE_PROFILES=postgres PROFILES=prod,postgres ;;
  *) echo "unknown PERSISTENCE '$PERSISTENCE' (memory|postgres)" >&2; exit 2 ;;
esac
# One compose project per port, so two runs (two people, two sessions) on one machine never remove each other's server.
export PORT=${PORT:-9858}
compose=(docker compose -f perf/compose.yaml -p "yukta-perf-$PORT")

sha=$(git rev-parse --short HEAD); dirty=$([[ -n $(git status --porcelain -- . ':!perf/reports') ]] && echo true || echo false)
mode=native
dir=$root/perf/reports/$type/$(date -u +%Y%m%dT%H%M%SZ)-$mode-$sha
mkdir -p "$dir"

cleanup() {
  [[ -n ${stats_pid:-} ]] && kill "$stats_pid" 2>/dev/null || true
  "${compose[@]}" logs --no-color yukta > "$dir/server.log" 2>&1 || true
  {
    for p in 'Tracker event lost' 'consumer stopped unexpectedly' ' ERROR '; do
      printf '%s: %s\n' "$p" "$(grep -c "$p" "$dir/server.log" || true)"
    done
  } > "$dir/diagnostics.txt"
  [[ ${KEEP:-} == 1 ]] && echo "server left running (KEEP=1): key=$API_KEY" || "${compose[@]}" down -v >/dev/null 2>&1
  echo "report: $dir"
}
trap cleanup EXIT

# notifications: STREAMS (200) stream clients need STREAMS/8 keys, the server allows 8 streams per key
if [[ $type == notifications ]]; then
  SCENARIO=${SCENARIO:-steady}
  case $SCENARIO in
    steady) export ANSWER_SECONDS=${ANSWER_SECONDS:-120} ANSWERS=${ANSWERS:-600} ;;   # 5 changes a second
    burst) export ANSWER_SECONDS=${ANSWER_SECONDS:-10} ANSWERS=${ANSWERS:-500} ;;
    *) echo "unknown SCENARIO '$SCENARIO' (steady|burst)" >&2; exit 2 ;;
  esac
fi
STREAMS=${STREAMS:-$([[ $CPUS -le 2 ]] && echo 100 || echo 200)}   # people with a tab open; 100 on 2 vCPU, 200 on 4
if [[ $type == notifications || $PERSISTENCE == postgres ]]; then
  KEYS_ENV=$dir/keys.env; : > "$KEYS_ENV"; export KEYS_ENV
fi
if [[ $PERSISTENCE == postgres ]]; then
  printf 'SPRING_R2DBC_URL=r2dbc:postgresql://postgres:5432/yukta\nSPRING_R2DBC_USERNAME=yukta\nSPRING_R2DBC_PASSWORD=perf-only\nSPRING_FLYWAY_URL=jdbc:postgresql://postgres:5432/yukta\nSPRING_FLYWAY_USER=yukta\nSPRING_FLYWAY_PASSWORD=perf-only\n' >> "$KEYS_ENV"
  # the postgres profile keeps only sessions, definitions, the log and agents there: checkpoints and input requests too
  printf 'YUKTA_RESTART_STORE_TYPE=postgres\nYUKTA_INPUT_STORE_TYPE=postgres\n' >> "$KEYS_ENV"
fi
if [[ $type == notifications ]]; then
  for i in $(seq 1 $(( (STREAMS + 7) / 8 ))); do
    printf 'YUKTA_API_KEYS_%d_NAME=stream%d\nYUKTA_API_KEYS_%d_KEY=stream-key-%d-%s\nYUKTA_API_KEYS_%d_ROLE=OPERATOR\nYUKTA_API_KEYS_%d_SESSIONS_0=*\n' \
      "$i" "$i" "$i" "$i" "$API_KEY" "$i" "$i" >> "$KEYS_ENV"
  done
fi
"${compose[@]}" up -d --wait
healthy=
for _ in $(seq 60); do
  curl -fs -H "Authorization: Bearer $API_KEY" localhost:$PORT/actuator/health >/dev/null && { healthy=1; break; }; sleep 1
done
[[ -n $healthy ]] || { echo "server did not become healthy within 60 s (see $dir/server.log)" >&2; exit 3; }

jq -n --arg test "$type" --arg mode "$mode" --arg commit "$sha" --argjson dirty "$dirty" --arg cpus "$CPUS" --arg cpuset "$CPUSET" \
  --arg memory "$MEMORY" --arg javaOpts "$JAVA_OPTS" --arg image "$IMAGE" --arg binary "${BINARY:-}" \
  --arg k6 "$(k6 version | head -1)" --arg artifact "$(basename "$JAR")" --arg sha256 "$(sha256sum "$JAR" | cut -c1-16)" \
  --arg port "$PORT" --arg docker "$(docker --version)" --arg rate "${RATE:-default}" --arg workflows "${WORKFLOWS:-default}" \
  --arg hold "${HOLD:-default}" --arg duration "${DURATION:-default}" --arg startedAt "$(date -u +%FT%TZ)" \
  --arg persistence "$PERSISTENCE" \
  '{test:$test,mode:$mode,commit:$commit,dirty:$dirty,cpus:$cpus,cpuset:$cpuset,memory:$memory,javaOpts:$javaOpts,image:$image,
    binary:$binary,k6:$k6,artifact:$artifact,artifactSha256:$sha256,port:$port,docker:$docker,rate:$rate,workflows:$workflows,
    persistence:$persistence,hold:$hold,duration:$duration,startedAt:$startedAt}' > "$dir/meta.json"

( echo 'time,cpu_perc,mem_usage'
  while sleep 5; do
    docker stats --no-stream --format "$(date -u +%T),{{.CPUPerc}},{{.MemUsage}}" yukta-perf-$PORT-yukta-1
  done ) > "$dir/container-stats.csv" 2>/dev/null &
stats_pid=$!

envs=()
for v in RATE RAMP HOLD DURATION WORKFLOWS LENIENT GRACE MAX_REJECTED SCENARIO ANSWERS ANSWER_SECONDS READERS READ_EVERY OPEN_REQUESTS; do
  [[ -n ${!v:-} ]] && envs+=(-e "$v=${!v}")
done

stream_pids=()
if [[ $type == notifications ]]; then
  for n in $(seq 0 $((STREAMS - 1))); do
    # every line gets the time it arrived ($EPOCHREALTIME, no fork), to measure the delivery lag
    ( curl -sN -m 3600 -H "Authorization: Bearer stream-key-$((n / 8 + 1))-$API_KEY" \
        -w '%{http_code}\n' localhost:$PORT/api/notifications/stream 2>/dev/null |
      while IFS= read -r line; do printf '%s %s\n' "$EPOCHREALTIME" "$line"; done > "$dir/stream-$n.out" ) &
    stream_pids+=($!)
  done
  sleep 2
fi
set +e
K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT="$dir/dashboard.html" K6_WEB_DASHBOARD_PERIOD=5s \
"${pin[@]}" k6 run "perf/k6/$type.js" \
  -e BASE_URL=http://localhost:$PORT "${envs[@]}" \
  --summary-export "$dir/summary.json" \
  --summary-trend-stats 'avg,min,med,p(90),p(95),p(99),max' \
  --out csv="$dir/metrics.csv" "$@" 2>&1 | tee "$dir/k6.log"
rc=${PIPESTATUS[0]}
if [[ $type == notifications ]]; then
  sleep 5
  # stopping the server ends every stream, so the clients finish and their files are complete
  "${compose[@]}" stop yukta >/dev/null 2>&1 || true
  [[ ${#stream_pids[@]} -eq 0 ]] || wait "${stream_pids[@]}" 2>/dev/null || true
  python3 perf/stream-lag.py "$dir" "$STREAMS" | tee -a "$dir/k6.log" || rc=1
  rm -f "$dir"/stream-*.out
fi
gzip -f "$dir/metrics.csv" 2>/dev/null
exit "$rc"
