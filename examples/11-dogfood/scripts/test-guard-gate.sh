#!/usr/bin/env bash
# Offline test of the decision-model gate of `yukta-heal.sh judge` (fake Ollama): a clean change the model calls unrelated is rejected only
# in mode on, shadow and an unreachable model leave the verdict as the rules gave it, and the files outside the failing module are listed.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap '[[ -z "${pid:-}" ]] || kill "$pid" 2>/dev/null || true; rm -rf "$dir"' EXIT
python3 -I "$here/scripts/fake-ollama.py" "$dir/port" &
pid=$!
for ((i = 0; i < 20; i++)); do [[ -s "$dir/port" ]] && break; sleep 0.25; done
proj="$dir/proj"
mkdir -p "$proj/core" "$proj/web"
git -C "$proj" init -q
echo 'int a;' >"$proj/core/A.java"; echo 'int w;' >"$proj/web/W.java"
git -C "$proj" add -A && git -C "$proj" -c user.email=t@t -c user.name=t commit -qm init
export YUKTA_HEAL_DIR="$dir/heal" YUKTA_STAGE=compile YUKTA_MODEL=haiku YUKTA_EFFORT=low YUKTA_MODULE=core \
  YUKTA_DEV_STATE_DIR="$dir/state" YUKTA_DECISION_URL="http://127.0.0.1:$(cat "$dir/port")" YUKTA_DECISION_MODEL=fake
mkdir -p "$YUKTA_HEAL_DIR"
echo 1 >"$YUKTA_HEAL_DIR/compile.attempt"
echo '{"claimed": true}' >"$YUKTA_HEAL_DIR/compile-1.claude"
: >"$YUKTA_HEAL_DIR/compile-1.before"
echo HEAD >"$YUKTA_HEAL_DIR/compile-1.base"
echo 'int a = 1;' >"$proj/core/A.java"; echo 'int w = 1;' >"$proj/web/W.java"   # the attempt's change: one file in core, one outside

# judge PROBLEMS: exit status of the judge for a failure whose first message is PROBLEMS
judge() { (cd "$proj" && YUKTA_PROBLEMS="$1" "$here/scripts/yukta-heal.sh" judge 2>/dev/null); }
verdict() { jq -r "$1" "$YUKTA_HEAL_DIR/compile-1.verdict"; }

judge "difficulty: unrelated" || fail "shadow mode rejected a clean change"
[[ "$(verdict .violations)" == "" ]] || fail "shadow: $(verdict .violations)"
[[ "$(verdict '.outside | join(",")')" == "web/W.java" ]] || fail "outside: $(verdict .outside)"
grep -q 'guard' "$dir/state/decisions.tsv" || fail "shadow mode recorded nothing"

YUKTA_DECISION_GUARD=on judge "difficulty: unrelated" && fail "an unrelated change passed with the gate on"
[[ "$(verdict .violations)" == *"model: the change looks unrelated"* ]] || fail "on: $(verdict .violations)"
YUKTA_DECISION_GUARD=on judge "difficulty: fine" || fail "a fine change was rejected"
YUKTA_DECISION_GUARD=on YUKTA_DECISION_URL=http://127.0.0.1:1 judge "difficulty: unrelated" || fail "an unreachable model rejected a change"
YUKTA_DECISION_GUARD=on YUKTA_DECISION_MODEL= judge "difficulty: unrelated" || fail "no model rejected a change"
echo "ok   guard-gate"
