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
  if [[ -e "fail-$t" ]]; then echo "error: $t failed (violation)" >&2; rc=1; fi
done
exit $rc
SH
cat >"$dir/claude" <<'SH'
#!/usr/bin/env bash
# fake claude: fixes the failing tasks only when run with the model named in .fake-fix-at
prompt="" model=""
while (($#)); do case "$1" in -p) prompt="$2"; shift 2 ;; --model) model="$2"; shift 2 ;; *) shift ;; esac; done
if [[ "$model" == "$(cat .fake-fix-at 2>/dev/null)" ]]; then
  for t in $(sed -n 's/^Gradle tasks: //p' <<<"$prompt"); do rm -f "fail-$t"; done
  echo "fix by $model" >>fixed.txt
  echo '{"total_cost_usd":0.01,"usage":{"input_tokens":10,"cache_creation_input_tokens":5,"cache_read_input_tokens":100,"output_tokens":3},"structured_output":{"fixed":true,"summary":"fake fix"}}'
else
  echo '{"total_cost_usd":0.005,"structured_output":{"fixed":false,"summary":"cannot fix"}}'
fi
SH
chmod +x "$proj/gradlew" "$dir/claude"
git -C "$proj" init -q
git -C "$proj" -c user.email=t@t -c user.name=t add -A
git -C "$proj" -c user.email=t@t -c user.name=t commit -qm init
