#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Smoke test a running server: wait for /actuator/health, apply the Quickstart session
# (release/smoke-session.json), run its workflow and check that the run succeeds. It also checks that plugins
# of each module family are registered and that the MCP endpoint lists its tools. It catches what a
# health check cannot, such as expressions failing in a native image.
#
# Usage: release/smoke-test.sh <base-url> [api-key]     e.g. release/smoke-test.sh http://127.0.0.1:9858 key
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -ge 1 ]] || die "usage: smoke-test.sh <base-url> [api-key]"
base="${1%/}"
key="${2:-}"
auth=()
[[ -z "$key" ]] || auth=(-H "X-API-Key: $key")

# Each response goes into a variable and is matched with a here-string: a pipe into `grep -q` or
# `head -1` ends early, and under pipefail the SIGPIPE on the writer would fail a correct answer.
health=""
for _ in $(seq 1 60); do
  health="$(curl -fsS "$base/actuator/health" 2>/dev/null || true)"
  grep -q '"status":"UP"' <<< "$health" && break
  sleep 2
done
grep -q '"status":"UP"' <<< "$health" || die "smoke test: /actuator/health never reported UP"

# Plugin loading and MCP: one plugin per module family, and the MCP tool list, both native-only risks.
plugins="$(curl -fsS "${auth[@]}" "$base/api/plugins")" || die "smoke test: could not list plugins"
for type in API_TRIGGER SCHEDULE_TRIGGER SQL_QUERY FILE_READ MCP_LIST_TOOLS WAIT_FOR_SIGNAL; do
  grep -q "\"$type\"" <<< "$plugins" || die "smoke test: plugin $type is not registered"
done
tools="$(curl -fsS -X POST "$base/mcp" "${auth[@]}" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}')" || die "smoke test: MCP tools/list failed"
grep -q start_workflow <<< "$tools" || die "smoke test: MCP tools/list does not expose start_workflow"

curl -fsS "${auth[@]}" -X POST "$base/api/sessions" -H "Content-Type: application/json" \
  -d @"$RELEASE_DIR/smoke-session.json" >/dev/null || die "smoke test: could not apply the Quickstart session"
started="$(curl -fsS "${auth[@]}" -X POST "$base/api/workflow/start" -H "Content-Type: application/json" \
  -d '{"sessionId":"demo","workflowId":"greet","payload":{"name":"Ada"}}')" ||
  die "smoke test: could not start the Quickstart workflow"
execution="$(sed -n 's/.*"executionId":"\([^"]*\)".*/\1/p' <<< "$started")"
[[ -n "$execution" ]] || die "smoke test: no executionId in: $started"

status=""
for _ in $(seq 1 30); do
  # The first quoted status is the run's; the per-node statuses follow it.
  run="$(curl -fsS "${auth[@]}" "$base/api/executions/$execution" || true)"
  status=""
  [[ "$run" =~ \"status\":\"([A-Z_]+)\" ]] && status="${BASH_REMATCH[1]}"
  case "$status" in RUNNING | PENDING | STARTING | "") sleep 1 ;; *) break ;; esac
done
[[ "$status" == "SUCCESS" ]] || die "smoke test: the Quickstart run ended as '$status', not SUCCESS (execution $execution)"
log "smoke test passed: Quickstart workflow succeeded"
