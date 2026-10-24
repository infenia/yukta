#!/usr/bin/env bash
# Offline test of `yk stats` with synthetic records.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
export YUKTA_DEV_STATE_DIR="$dir/state" YUKTA_REPO_ROOT="$dir/work/yukta"
mkdir -p "$dir/work/yukta" "$YUKTA_DEV_STATE_DIR/heal/s1" "$YUKTA_DEV_STATE_DIR/issue-7" "$dir/work/yukta-issue-7/.yukta-dev/heal/check"

[[ "$("$here/../yk" stats)" == *"no records yet"* ]] || fail "empty state is not reported"

rec() { echo "$2" >"$1"; }
rec "$YUKTA_DEV_STATE_DIR/heal/s1/compile-1.json" '{"stage":"compile","attempt":1,"mode":"haiku","fixed":false,"cost":0.01,"usage":{"input":10,"cache_creation":90,"cache_read":0,"output":5}}'
rec "$YUKTA_DEV_STATE_DIR/heal/s1/compile-2.json" '{"stage":"compile","attempt":2,"mode":"haiku","fixed":true,"cost":0.02,"usage":{"input":10,"cache_creation":10,"cache_read":180,"output":5}}'
rec "$YUKTA_DEV_STATE_DIR/heal/s1/spotless-0.json" '{"stage":"spotless","attempt":0,"mode":"auto","fixed":true,"cost":0,"usage":null}'
rec "$dir/work/yukta-issue-7/.yukta-dev/heal/check/tests-1.json" '{"stage":"tests","attempt":1,"mode":"resume","fixed":true,"cost":0.5,"usage":{"input":1,"cache_creation":0,"cache_read":99,"output":5}}'
rec "$YUKTA_DEV_STATE_DIR/issue-7/implement.json" '{"cost":1.25,"usage":{"input_tokens":50,"cache_creation_input_tokens":50,"cache_read_input_tokens":0,"output_tokens":9},"done":true,"summary":"x"}'

out="$("$here/../yk" stats)"
row() { grep -E "^$1 " <<<"$out" | tr -s ' '; }
[[ "$(row 'compile haiku')" == "compile haiku 2 1 \$0.03 60%" ]] || fail "compile haiku: $(row 'compile haiku')"
[[ "$(row 'spotless auto')" == "spotless auto 1 1 \$0 -" ]] || fail "spotless auto: $(row 'spotless auto')"
[[ "$(row 'tests resume')" == "tests resume 1 1 \$0.5 99%" ]] || fail "tests resume: $(row 'tests resume')"
[[ "$(row 'implement')" == "implement 1 1 \$1.25 0%" ]] || fail "implement: $(row implement)"
[[ "$(row 'total')" == "total 5 4 \$1.78 "* ]] || fail "total: $(row total)"
head -1 <<<"$out" | grep -q "step *attempts *fixed *cost *cache-read" || fail "no header: $out"
echo "ok   stats"
