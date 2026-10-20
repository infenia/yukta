#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Prints the Gradle tasks one CI shard must run (empty output = nothing to do).
#
# Usage: ci-tasks.sh <platform|infra|plugins|frontend>
# Env:   FULL=true            run every module of the shard (main, schedule, root-file change)
#        CHANGED_MODULES      JSON array of changed module names (paths-filter output)
#
# Rules on a partial (PR) build:
#   * a changed library module (core, web, messaging, plugin-api, mcp) re-runs every shard, as
#     everything consumes it (this is what buildDependents did before);
#   * otherwise a shard runs just the changed modules it owns;
#   * boot consumes plugins, infra modules and ui, so a change to any of those re-runs boot too.
set -euo pipefail

shard=${1:?shard name required}
FULL=${FULL:-false}

declare -A path=(
  [messaging]=:messaging [plugin-api]=:plugin-api [core]=:core [web]=:web [mcp]=:mcp [boot]=:boot
  [persistence-postgres]=:persistence-postgres [plus]=:plus [workers]=:workers [cli]=:cli [ui]=:ui
  [api-trigger]=:plugins:triggers:api-trigger
  [constant-source]=:plugins:triggers:constant-source
  [schedule-trigger]=:plugins:triggers:schedule-trigger
  [console-terminal]=:plugins:terminals:console-terminal
  [notify-terminal]=:plugins:terminals:notify-terminal
  [internal-core]=:plugins:processors:internal:internal-core
  [script-js]=:plugins:processors:internal:script-js
  [process-executor]=:plugins:processors:process-executor
  [http-request]=:plugins:processors:http-request
  [llm-call]=:plugins:processors:llm-call
  [mcp-tool-call]=:plugins:processors:mcp-tool-call
  [session-memory]=:plugins:processors:session-memory
  [decision]=:plugins:processors:decision
  [file-io]=:plugins:processors:file-io
  [sql-query]=:plugins:processors:sql-query
  [rule-supervisor]=:plugins:supervisors:rule-supervisor
  [rule-control-plane]=:plugins:control-planes:rule-control-plane
  [model-control-plane]=:plugins:control-planes:model-control-plane
  [rules-first-control-plane]=:plugins:control-planes:rules-first-control-plane
)

group_of() {
  case $1 in
    messaging|plugin-api|core|web|mcp|boot) echo platform ;;
    persistence-postgres|plus|workers) echo infra ;;
    cli|ui) echo frontend ;;
    *) echo plugins ;;
  esac
}

changed=()
if [ "$FULL" != true ]; then
  mapfile -t changed < <(jq -r '.[]' <<<"${CHANGED_MODULES:-[]}")
fi

all_in_shard=()
for m in "${!path[@]}"; do
  [ "$(group_of "$m")" = "$shard" ] && all_in_shard+=("$m")
done

selected=()
if [ "$FULL" = true ]; then
  selected=("${all_in_shard[@]}")
else
  lib=false
  boot=false
  for m in "${changed[@]}"; do
    [ -n "${path[$m]:-}" ] || { echo "::error::Unrecognized module '$m' - add it to ci-tasks.sh" >&2; exit 1; }
    case $m in core|web|messaging|plugin-api|mcp) lib=true ;; esac
    case $(group_of "$m") in plugins|infra) boot=true ;; esac
    [ "$m" = ui ] && boot=true
  done
  if [ "$lib" = true ]; then
    selected=("${all_in_shard[@]}")
  else
    for m in "${changed[@]}"; do
      [ "$(group_of "$m")" = "$shard" ] && selected+=("$m")
    done
    if [ "$boot" = true ] && [ "$shard" = platform ]; then selected+=(boot); fi
  fi
fi

# De-duplicate and print as :module:build tasks.
[ ${#selected[@]} -gt 0 ] || { echo; exit 0; }
printf '%s\n' "${selected[@]}" | sort -u | while read -r m; do
  [ -n "$m" ] && printf '%s:build ' "${path[$m]}"
done
echo
