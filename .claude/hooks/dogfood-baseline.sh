#!/usr/bin/env bash
# SessionStart hook: remember what the uncommitted code changes looked like when the session began, so
# dogfood-nudge.sh only asks for the staged check when the session itself changed something.
set -uo pipefail
[[ "${YUKTA_DOGFOOD:-on}" == off ]] && exit 0
sid="$(jq -r '.session_id // empty' 2>/dev/null)"
[[ -n "$sid" ]] || exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
mkdir -p .yukta-dev
find .yukta-dev -maxdepth 1 -name 'baseline-*' -mtime +7 -delete 2>/dev/null
file=".yukta-dev/baseline-$sid"
[[ -e "$file" ]] && exit 0   # a resumed session keeps its first baseline
changes="$(git status --porcelain -- '*.java' '*.kts' '*.gradle' '*.go' '*.ts' '*.tsx' '*.json' 2>/dev/null)"
( git diff HEAD; echo "$changes" ) 2>/dev/null | sha1sum | cut -d' ' -f1 >"$file"
