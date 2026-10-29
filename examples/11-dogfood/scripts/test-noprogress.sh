#!/usr/bin/env bash
# Offline test of the no-progress check of `yukta-heal.sh report`: an attempt after which the stage fails exactly as it did after the
# attempt before (numbers aside) says same=true, which stops the ladder (gen-heal.py); a different failure, or the first attempt, does not.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
git -C "$dir" init -q
export YUKTA_HEAL_DIR="$dir/heal" YUKTA_STAGE=compile YUKTA_MODEL=haiku YUKTA_EFFORT=low
mkdir -p "$YUKTA_HEAL_DIR"

# report N STDOUT: the report step of attempt N after a recheck that failed with STDOUT; prints the `same` flag
report() {
  echo "$1" >"$YUKTA_HEAL_DIR/compile.attempt"
  echo '{"cost": 0, "summary": "tried", "claimed": true}' >"$YUKTA_HEAL_DIR/compile-$1.claude"
  jq -nc --arg out "$2" '{results: [{input: {label: "compile"}, success: false, stdout: $out}]}' \
    | (cd "$dir" && "$here/scripts/yukta-heal.sh" report) | jq -r .same
}

[[ "$(report 1 'src/A.java:20: error: boom')" == false ]] || fail "the first attempt has nothing to repeat"
[[ "$(report 2 'src/A.java:21: error: boom')" == true ]] || fail "the same failure (line number aside) was not seen"
[[ "$(report 3 'src/B.java:5: error: other')" == false ]] || fail "a different failure counted as the same"
[[ "$(report 4 'src/B.java:5: error: other')" == true ]] || fail "the repeat of a new failure was not seen"
echo "ok   noprogress"
