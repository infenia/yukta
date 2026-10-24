#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Run a k6 test against Yukta in a container with fixed resources and keep a report.
#   perf/run.sh <perf|load|soak> [extra k6 args]
# Env: PORT=8080 (the host port; a run on another port has its own compose project) CPUS=2 CPUSET=0,1 MEMORY=8g K6_CPUSET=2-15 JAVA_OPTS=... IMAGE=... KEEP=1 (leave server up)
#      MODE=native builds the native binary (release/build-server.sh; NATIVE_QUICK=1, NATIVE_HEAP=24g) and runs it
#      BINARY=dist/linux-amd64/yukta runs an existing native binary without building (image debian:12-slim)
#      plus the k6 -e variables, e.g. RATE=50 WORKFLOWS=mapper DURATION=10m
set -euo pipefail

type=${1:?usage: run.sh <perf|load|soak> [k6 args]}; shift
[[ $type =~ ^(perf|load|soak)$ ]] || { echo "unknown test '$type'" >&2; exit 2; }
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

export CPUS=${CPUS:-2} CPUSET=${CPUSET:-0,1} MEMORY=${MEMORY:-8g}
export JAVA_OPTS=${JAVA_OPTS:--XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Dyukta.rate-limit-per-minute=0}
export API_KEY=${API_KEY:-$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')}
case ${MODE:-jar} in
  jar) ;;
  native)
    release/build-server.sh
    BINARY=dist/linux-$([[ $(uname -m) == @(aarch64|arm64) ]] && echo arm64 || echo amd64)/yukta ;;
  *) echo "unknown MODE '$MODE' (jar|native)" >&2; exit 2 ;;
esac
[[ -n ${JAR:-} || -n ${BINARY:-} ]] || ./gradlew :boot:bootJar -q --console=plain  # no-op when up to date; never run a stale jar
if [[ -z ${JAR:-} && -z ${BINARY:-} ]]; then
  # The jar is named after the project version: take the newest one, never the first by name (an old
  # boot-*.jar left in build/libs would be benchmarked). A regular file: Docker leaves a directory
  # behind when it is asked to mount a path that does not exist.
  for candidate in "$root"/boot/build/libs/boot-*.jar; do
    [[ -f $candidate && $candidate != *-plain.jar ]] || continue
    if [[ -z ${JAR:-} || $candidate -nt $JAR ]]; then JAR=$candidate; fi
  done
  [[ -n ${JAR:-} ]] || { echo "no boot jar in boot/build/libs" >&2; exit 2; }
fi
export JAR
if [[ -n ${BINARY:-} ]]; then
  JAR=$(realpath "$BINARY"); export RUN_CMD=/app/yukta.jar IMAGE=${IMAGE:-debian:12-slim}   # mounted at the jar's path
  [[ -f $JAR && -x $JAR ]] || { echo "BINARY $JAR is not an executable file" >&2; exit 2; }
fi
# One compose project per port, so two runs (two people, two sessions) on one machine never remove each other's server.
export PORT=${PORT:-8080}
compose=(docker compose -f perf/compose.yaml -p "yukta-perf-$PORT")

sha=$(git rev-parse --short HEAD); dirty=$([[ -n $(git status --porcelain -- . ':!perf/reports') ]] && echo true || echo false)
dir=$root/perf/reports/$type/$(date -u +%Y%m%dT%H%M%SZ)-$sha
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

"${compose[@]}" up -d --wait
for _ in $(seq 60); do
  curl -fs -H "Authorization: Bearer $API_KEY" localhost:$PORT/actuator/health >/dev/null && break; sleep 1
done

cat > "$dir/meta.json" <<JSON
{"test":"$type","commit":"$sha","dirty":$dirty,"cpus":"$CPUS","cpuset":"$CPUSET","memory":"$MEMORY",
 "javaOpts":"$JAVA_OPTS","image":"${IMAGE:-eclipse-temurin:25-jre}","binary":$(jq -Rn --arg b "${BINARY:-}" '$b'),"k6":"$(k6 version | head -1)",
 "artifact":"$(basename "$JAR")","artifactSha256":"$(sha256sum "$JAR" | cut -c1-16)","port":"$PORT","docker":"$(docker --version)","rate":"${RATE:-default}","workflows":"${WORKFLOWS:-default}",
 "hold":"${HOLD:-default}","duration":"${DURATION:-default}","startedAt":"$(date -u +%FT%TZ)"}
JSON

( echo 'time,cpu_perc,mem_usage'
  while sleep 5; do
    docker stats --no-stream --format "$(date -u +%T),{{.CPUPerc}},{{.MemUsage}}" yukta-perf-$PORT-yukta-1
  done ) > "$dir/container-stats.csv" 2>/dev/null &
stats_pid=$!

envs=()
for v in RATE RAMP HOLD DURATION WORKFLOWS LENIENT GRACE MAX_REJECTED; do
  [[ -n ${!v:-} ]] && envs+=(-e "$v=${!v}")
done

set +e
K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT="$dir/dashboard.html" K6_WEB_DASHBOARD_PERIOD=5s \
taskset -c "${K6_CPUSET:-2-15}" k6 run "perf/k6/$type.js" \
  -e BASE_URL=http://localhost:$PORT -e API_KEY="$API_KEY" "${envs[@]}" \
  --summary-export "$dir/summary.json" \
  --summary-trend-stats 'avg,min,med,p(90),p(95),p(99),max' \
  --out csv="$dir/metrics.csv" "$@" 2>&1 | tee "$dir/k6.log"
rc=${PIPESTATUS[0]}
gzip -f "$dir/metrics.csv" 2>/dev/null
exit "$rc"
