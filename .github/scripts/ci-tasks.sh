#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Plans a CI run: reads the changed files on stdin and prints one JSON document saying what to run.
#
# Usage: git diff --name-only BASE HEAD | ci-tasks.sh
#        ci-tasks.sh --check          validate the module graph only (lint-workflows)
# Env:   FULL=true                    run every module (main, schedule, dispatch, `ci:full` label)
#        REPO_ROOT                    repository to read the module graph from (default: git top level)
#
# Output: {"full": bool, "changed": [...], "affected": [...], "shards": [{name, args}],
#          "lint": "<gradle tasks>", "e2e": bool}
#
# Nothing here is maintained by hand: modules come from settings.gradle.kts and the edges between
# them from the `project(":x")` references in each module's build.gradle.kts. On a PR:
#   * a file under a module marks that module changed (longest path wins: plus/workers -> :plus:workers);
#   * a root/build file (build-logic, gradle/, config/, workflows, ...) forces a full run;
#   * any other file (docs, examples, *.md) runs nothing;
#   * every module that depends on a changed module, directly or transitively, is rebuilt too.
set -euo pipefail

root=${REPO_ROOT:-$(git rev-parse --show-toplevel)}
FULL=${FULL:-false}

mapfile -t modules < <(sed -n 's/^include("\(.*\)")/:\1/p' "$root/settings.gradle.kts")
[ ${#modules[@]} -gt 0 ] || { echo "::error::No modules found in settings.gradle.kts" >&2; exit 1; }

declare -A known=() rdeps=()
for m in "${modules[@]}"; do known[$m]=1; done

# Reverse edges: rdeps[:dep] = modules whose build file names ":dep". Any quoted module path counts,
# not just project(":dep"): boot picks its optional plugins from a map of path strings.
errors=0
for m in "${modules[@]}"; do
  build="$root/${m#:}"
  build="${build//://}/build.gradle.kts"
  [ -f "$build" ] || continue
  while read -r dep; do
    if [ -z "${known[$dep]:-}" ]; then
      echo "::error::${m} depends on ${dep}, which is not included in settings.gradle.kts" >&2
      errors=$((errors + 1))
    fi
  done < <(grep -oE 'project\(":[^"]+"\)' "$build" | sed 's/project("\(.*\)")/\1/' | sort -u)
  while read -r dep; do
    [ -n "${known[$dep]:-}" ] && [ "$dep" != "$m" ] && rdeps[$dep]="${rdeps[$dep]:-} $m"
  done < <(grep -oE '":[A-Za-z0-9_:-]+"' "$build" | tr -d '"' | sort -u)
done
[ "$errors" -eq 0 ] || exit 1

if [ "${1:-}" = --check ]; then
  echo "ci-tasks.sh: ${#modules[@]} modules, every project dependency resolves"
  exit 0
fi

group_of() {
  case $1 in
    :plugins:*) echo plugins ;;
    :plus|:plus:*) echo infra ;;
    :cli) echo cli ;;
    :ui) echo ui ;;
    *) echo platform ;;
  esac
}

# Files that change how every module builds.
is_root_file() {
  case $1 in
    .github/workflows/*|.github/scripts/*|build-logic/*|gradle/*|config/*) return 0 ;;
    build.gradle.kts|settings.gradle.kts|gradle.properties|gradlew|gradlew.bat) return 0 ;;
    .java-version|lombok.config) return 0 ;;
    *.lockfile) [[ $1 != */* ]] ;;
    *) return 1 ;;
  esac
}

# The module owning a path (deepest module directory that prefixes it), or nothing.
module_of() {
  local f=$1 best='' dir
  for m in "${modules[@]}"; do
    dir="${m#:}"
    dir="${dir//://}"
    if [[ $f == "$dir"/* ]] && [ ${#dir} -gt ${#best} ]; then best=$dir; fi
  done
  [ -n "$best" ] && echo ":${best//\//:}"
  return 0
}

declare -A changed=() affected=()
full=$FULL
if [ "$full" != true ]; then
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if is_root_file "$f"; then full=true; break; fi
    m=$(module_of "$f")
    [ -n "$m" ] && changed[$m]=1
  done
fi

if [ "$full" = true ]; then
  for m in "${modules[@]}"; do affected[$m]=1; done
else
  queue=("${!changed[@]}")
  while [ ${#queue[@]} -gt 0 ]; do
    m=${queue[0]}
    queue=("${queue[@]:1}")
    [ -z "${affected[$m]:-}" ] || continue
    affected[$m]=1
    for d in ${rdeps[$m]:-}; do queue+=("$d"); done
  done
fi

sorted() { [ $# -eq 0 ] || printf '%s\n' "$@" | sort; }
mapfile -t affected_list < <(sorted "${!affected[@]}")
mapfile -t changed_list < <(sorted "${!changed[@]}")

shards='[]'
lint=''
for shard in platform infra plugins cli ui; do
  tasks=''
  for m in "${affected_list[@]}"; do
    [ "$(group_of "$m")" = "$shard" ] && tasks="$tasks$m:build "
  done
  [ -n "$tasks" ] || continue
  shards=$(jq -c --arg n "$shard" --arg a "${tasks}--continue" '. + [{name: $n, args: $a}]' <<<"$shards")
done

# Fail-fast lint: Checkstyle/PMD/OpenGrep only (cli and ui have none; boot needs the UI build).
for m in "${affected_list[@]}"; do
  case $m in :cli|:ui) continue ;; esac
  lint="$lint $m:opengrep"
  [ "$m" = :boot ] || lint="$lint $m:checkstyleMain $m:checkstyleTest $m:pmdMain $m:pmdTest"
done

# End-to-end runs of the whole app: when the store they exercise (or anything it depends on) or
# boot itself changed.
if [ "$full" = true ] || [ -n "${affected[:plus:persistence-postgres]:-}" ] || [ -n "${changed[:boot]:-}" ]; then
  shards=$(jq -c '. + [{name: "postgres-acceptance",
    args: "-Pyukta.persistence=postgres :boot:test --tests *PostgresPersistenceAcceptanceTest"}]' <<<"$shards")
fi
if [ "$full" = true ] || [ -n "${affected[:plus]:-}" ] || [ -n "${changed[:boot]:-}" ]; then
  shards=$(jq -c '. + [{name: "plus-acceptance",
    args: "-Pyukta.edition=plus :boot:test --tests *FileStoreAcceptanceTest"}]' <<<"$shards")
fi

e2e=false
if [ "$full" = true ] || [ -n "${changed[:ui]:-}" ]; then e2e=true; fi

jq -nc --argjson full "$full" --argjson e2e "$e2e" --argjson shards "$shards" --arg lint "${lint# }" \
  --args '{full: $full, changed: $ARGS.positional[0] | split(" ") | map(select(. != "")),
           affected: $ARGS.positional[1] | split(" ") | map(select(. != "")),
           shards: $shards, lint: $lint, e2e: $e2e}' \
  "${changed_list[*]:-}" "${affected_list[*]:-}"
