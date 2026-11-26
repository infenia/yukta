#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Remote workers between two native binaries: a control plane and a worker, each built as its role
# (-Pyukta.workers=true -Pyukta.workers.native=control-plane|worker). Checks that the worker registers
# (GET /api/workers lists it) and that a placed node runs on it: the Quickstart workflow with its MAPPER
# node placed in the default pool must succeed, so the worker and node frames cross in both directions.
# Usage: [WORKERS_SMOKE_PORT=9858] release/workers-smoke.sh <control-plane-binary> <worker-binary>
# (the control plane serves HTTP on that port, the worker on the next one, RSocket on 7000)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 2 ]] || die "usage: workers-smoke.sh <control-plane-binary> <worker-binary>"
need curl
need jq
control_plane="$1"
worker="$2"
port="${WORKERS_SMOKE_PORT:-9858}"
base="http://127.0.0.1:$port"
token="workers-smoke-$RANDOM$RANDOM"
logs="$(mktemp -d)"

YUKTA_WORKERS_TOKEN="$token" SPRING_RSOCKET_SERVER_ADDRESS=127.0.0.1 SPRING_RSOCKET_SERVER_PORT=7000 \
  SERVER_PORT="$port" \
  "$control_plane" >"$logs/control-plane.log" 2>&1 &
cp_pid=$!
YUKTA_WORKERS_CONTROL_PLANE=127.0.0.1:7000 YUKTA_WORKERS_TOKEN="$token" YUKTA_WORKERS_ID=native-w1 \
  YUKTA_WORKERS_PLUGIN_TYPES=MAPPER SERVER_PORT="$((port + 1))" \
  "$worker" >"$logs/worker.log" 2>&1 &
worker_pid=$!
finish() {
  local code=$?
  kill "$worker_pid" "$cp_pid" 2>/dev/null || true
  if [[ $code -ne 0 ]]; then
    echo "--- control plane log (tail)"; tail -n 60 "$logs/control-plane.log"
    echo "--- worker log (tail)"; tail -n 60 "$logs/worker.log"
  fi
  rm -rf "$logs"
}
trap finish EXIT

workers=""
for _ in $(seq 1 90); do
  workers="$(curl -fsS "$base/api/workers" 2>/dev/null || true)"
  grep -q '"native-w1"' <<<"$workers" && break
  sleep 2
done
grep -q '"native-w1"' <<<"$workers" || die "workers smoke: the worker never registered: $workers"
log "the worker registered with the control plane"

jq '.sessionId = "placed" | .workflows.greet.nodes[1].config.engine = {placement: {pool: "default"}}' \
  "$RELEASE_DIR/smoke-session.json" |
  curl -fsS -X POST "$base/api/sessions" -H "Content-Type: application/json" -d @- >/dev/null ||
  die "workers smoke: could not apply the session with a placed node"
started="$(curl -fsS -X POST "$base/api/workflow/start" -H "Content-Type: application/json" \
  -d '{"sessionId":"placed","workflowId":"greet","payload":{"name":"Ada"}}')" ||
  die "workers smoke: could not start the workflow"
execution="$(sed -n 's/.*"executionId":"\([^"]*\)".*/\1/p' <<<"$started")"
[[ -n "$execution" ]] || die "workers smoke: no executionId in: $started"

status=""
for _ in $(seq 1 60); do
  run="$(curl -fsS "$base/api/executions/$execution" || true)"
  status=""
  [[ "$run" =~ \"status\":\"([A-Z_]+)\" ]] && status="${BASH_REMATCH[1]}"
  case "$status" in RUNNING | PENDING | STARTING | QUEUED | "") sleep 1 ;; *) break ;; esac
done
[[ "$status" == "SUCCESS" ]] ||
  die "workers smoke: the run with a placed node ended as '$status', not SUCCESS (execution $execution)"
log "workers smoke passed: a placed node ran on the worker"
