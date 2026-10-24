#!/usr/bin/env bash
# Stop hook: when Claude is about to finish and the session changed uncommitted code, ask it once per
# change set to run the staged check (yukta-check.sh) instead of stopping. Changes that were already
# there when the session began (dogfood-baseline.sh) do not count. Opt out with YUKTA_DOGFOOD=off.
set -uo pipefail
[[ "${YUKTA_DOGFOOD:-on}" == off ]] && exit 0
sid="$(jq -r '.session_id // empty' 2>/dev/null)"
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
changes="$(git status --porcelain -- '*.java' '*.kts' '*.gradle' '*.go' '*.ts' '*.tsx' '*.json' 2>/dev/null)"
[[ -n "$changes" ]] || exit 0
mark=".yukta-dev/nudged"
sum="$( (git diff HEAD; echo "$changes") | sha1sum | cut -d' ' -f1)"
# nothing changed since the session began: the changes are someone else's
[[ -z "$sid" || "$(cat ".yukta-dev/baseline-$sid" 2>/dev/null)" != "$sum" ]] || exit 0
[[ "$(cat "$mark" 2>/dev/null)" != "$sum" ]] || exit 0
mkdir -p .yukta-dev && echo "$sum" >"$mark"
echo '{"decision":"block","reason":"Uncommitted code changes: run the staged check before finishing (yukta skill: examples/11-dogfood/scripts/yukta-check.sh --watch, scoped with -m <module>), then report any Yukta defect with report-finding.sh. Set YUKTA_DOGFOOD=off to skip."}'
