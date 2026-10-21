#!/usr/bin/env bash
# Stop hook: when Claude is about to finish with uncommitted code changes, ask it once per change set
# to run the staged check (yukta-check.sh) instead of stopping. Opt out with YUKTA_DOGFOOD=off.
set -uo pipefail
[[ "${YUKTA_DOGFOOD:-on}" == off ]] && exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
changes="$(git status --porcelain -- '*.java' '*.kts' '*.gradle' '*.go' '*.ts' '*.tsx' '*.json' 2>/dev/null)"
[[ -n "$changes" ]] || exit 0
mark=".yukta-dev/nudged"
sum="$( (git diff HEAD; echo "$changes") | sha1sum | cut -d' ' -f1)"
[[ "$(cat "$mark" 2>/dev/null)" != "$sum" ]] || exit 0
mkdir -p .yukta-dev && echo "$sum" >"$mark"
echo '{"decision":"block","reason":"Uncommitted code changes: run the staged check before finishing (yukta skill: examples/11-dogfood/scripts/yukta-check.sh --watch, scoped with -m <module>), then report any Yukta defect with report-finding.sh. Set YUKTA_DOGFOOD=off to skip."}'
