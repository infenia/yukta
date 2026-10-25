#!/usr/bin/env bash
# fake-project.sh DIR: a throwaway git project with a fake ./gradlew and a fake claude, for the online
# tests of the check (test-self-heal.sh, test-steer.sh). Prints nothing; DIR/proj is the project and
# DIR/claude the fake claude (YUKTA_CLAUDE_BIN).
#   fake gradlew  a task fails while the file fail-<task> exists, sleeps 33 s while slow-<task> exists;
#                 spotlessApply formats
#   fake claude   fixes the failing tasks only when run with the model named in .fake-fix-at
set -euo pipefail
dir="${1:?usage: fake-project.sh DIR}"
proj="$dir/proj"
mkdir -p "$proj"
cat >"$proj/gradlew" <<'SH'
#!/usr/bin/env bash
# fake gradle: a task fails while the file fail-<task> exists; spotlessApply formats
rc=0
for t in "$@"; do
  case "$t" in -*) continue ;; esac
  if [[ "$t" == spotlessApply ]]; then rm -f fail-spotlessCheck; echo formatted >>src.txt; continue; fi
  if [[ -e "slow-$t" ]]; then sleep 33; fi
  if [[ -e "fail-$t" ]]; then echo "error: $t failed (violation)" >&2; [[ -e difficulty ]] && echo "difficulty: $(cat difficulty)" >&2; rc=1; fi
done
exit $rc
SH
cat >"$dir/claude" <<'SH'
#!/usr/bin/env bash
# fake claude. As the implementer (the prompt starts with "# Issue #") it writes a change and .yukta-dev/pr.md
# (and, when the file "implement-breaks" is next to this script, breaks compileJava). As a fixer it fixes the
# failing tasks only when run with the model (or "resume") named in .fake-fix-at (in the project) or "fix-at"
# (next to this script); a value like sonnet/high also names the effort (--effort). Every call is logged in claude.log next to this script.
here="$(cd "$(dirname "$0")" && pwd)"
prompt="" model="" session="" resume="" mcp="" effort=""
while (($#)); do case "$1" in -p) prompt="$2"; shift 2 ;; --model) model="$2"; shift 2 ;; --session-id) session="$2"; shift 2 ;; --resume) resume="$2"; shift 2 ;; --mcp-config) mcp="$2"; shift 2 ;; --effort) effort="$2"; shift 2 ;; *) shift ;; esac; done
echo "$(date +%s.%N) model=${model:--} effort=${effort:--} session=${session:--} resume=${resume:--} mcp=${mcp:--} cwd=$PWD" >>"$here/claude.log"
grep -q 'code index of this worktree' <<<"$prompt" && echo "nav-note" >>"$here/claude.log"
if [[ "$prompt" == "# Issue #"* ]]; then
  # "implement-sleep" next to this script makes the implementer take that many seconds (to see runs overlap)
  [[ -s "$here/implement-sleep" ]] && sleep "$(cat "$here/implement-sleep")"
  echo "implemented" >>feature.txt
  mkdir -p .yukta-dev
  printf 'feat: fake change\n\nWhat the fake implementer changed.\n' >.yukta-dev/pr.md
  [[ -e "$here/implement-breaks" ]] && touch fail-compileJava
  echo '{"total_cost_usd":0.5,"usage":{"input_tokens":10,"cache_creation_input_tokens":5,"cache_read_input_tokens":100,"output_tokens":3},"structured_output":{"done":true,"summary":"fake implementation"}}'
  exit 0
fi
want="$model"
at="$(cat .fake-fix-at 2>/dev/null || cat "$here/fix-at" 2>/dev/null)"
[[ "$at" == */* ]] && want="$model/$effort"
[[ -n "$resume" ]] && want=resume
if [[ "$want" == "$at" ]]; then
  for t in $(sed -n 's/^Gradle tasks: //p' <<<"$prompt"); do rm -f "fail-$t"; done
  echo "fix by $want" >>fixed.txt
  echo '{"total_cost_usd":0.01,"usage":{"input_tokens":10,"cache_creation_input_tokens":5,"cache_read_input_tokens":100,"output_tokens":3},"structured_output":{"fixed":true,"summary":"fake fix"}}'
else
  echo '{"total_cost_usd":0.005,"structured_output":{"fixed":false,"summary":"cannot fix"}}'
fi
SH
cat >"$dir/gh" <<'SH'
#!/usr/bin/env bash
# fake gh for yukta-issue.sh: every issue is open and assigned to "tester"; an issue's project status is the file
# "status-<n>" next to this script, else "status", else Ready; the sub-issues of an epic are the numbers in the file
# "sub-issues" (7 and 8 by default); "pr create" is logged in gh.log and answers a URL.
here="$(cd "$(dirname "$0")" && pwd)"
echo "$*" >>"$here/gh.log"
q="" args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do [[ "${args[i]}" == -q ]] && q="${args[i + 1]}"; done
case "$1 $2" in
  "api user") echo tester; exit 0 ;;
  "auth token") echo tok; exit 0 ;;
  "pr create") echo "https://example.invalid/pull/1"; exit 0 ;;
  "issue view")
    n="$3"
    out="$(jq -nc --argjson n "$n" '{number: $n, title: ("Fake issue " + ($n | tostring)),
      url: ("https://example.invalid/issues/" + ($n | tostring)), state: "OPEN", assignees: [{login: "tester"}],
      body: "Do the fake thing.\n\nAcceptance: it works."}')" ;;
  "project item-list")
    out='{"items":[]}'
    for n in 7 8 9; do
      st="$(cat "$here/status-$n" 2>/dev/null || cat "$here/status" 2>/dev/null || echo Ready)"
      out="$(jq -c --argjson n "$n" --arg st "$st" '.items += [{content: {number: $n}, status: $st}]' <<<"$out")"
    done ;;
  "api repos/"*)
    out='[]'
    for n in $(cat "$here/sub-issues" 2>/dev/null || echo 7 8); do
      out="$(jq -c --argjson n "$n" '. += [{number: $n, state: "open", assignees: [{login: "tester"}]}]' <<<"$out")"
    done ;;
  *) echo "fake gh: unsupported: $*" >&2; exit 1 ;;
esac
if [[ -n "$q" ]]; then jq -r "$q" <<<"$out"; else echo "$out"; fi
SH
cat >"$dir/project-status" <<'SH'
#!/usr/bin/env bash
# fake project update: logs "<url> <status>" in project-status.log next to this script and keeps the status
here="$(cd "$(dirname "$0")" && pwd)"
echo "$1 $2" >>"$here/project-status.log"
echo "$2" >"$here/status-${1##*/}"
SH
chmod +x "$proj/gradlew" "$dir/claude" "$dir/gh" "$dir/project-status"
git -C "$proj" init -q
git -C "$proj" -c user.email=t@t -c user.name=t add -A
git -C "$proj" -c user.email=t@t -c user.name=t commit -qm init
