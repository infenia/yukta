#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Offline test (no API cost): every schema accepts its valid fixture and rejects each invalid one; every ticket role runs through
# claude.sh against a fake claude, gets its own constant role file, and its answer validates against its schema.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dogfood="$here/../11-dogfood"
fail() { echo "FAIL $*" >&2; exit 1; }
tk="$here/ticketkit"

for dir in "$here"/fixtures/*/; do
  name="$(basename "$dir")"
  "$tk" validate-schema "$name" "$dir/valid.json" >/dev/null || fail "$name: valid.json rejected"
  n=0
  for bad in "$dir"/invalid-*.json; do
    "$tk" validate-schema "$name" "$bad" >/dev/null && fail "$name: $(basename "$bad") accepted"
    n=$((n + 1))
  done
  ((n >= 2)) || fail "$name: needs at least two invalid fixtures"
done
for s in "$here"/schemas/*.schema.json; do
  [[ -d "$here/fixtures/$(basename "$s" .schema.json)" ]] || fail "$(basename "$s"): no fixtures"
done
"$tk" validate-schema nope /dev/null 2>&1 | grep -q "no schema" && fail "unknown schema must exit non-zero" || true
"$tk" bogus >/dev/null 2>&1 && fail "unknown subcommand must exit non-zero" || true

dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
cat >"$dir/claude" <<'SH'
#!/usr/bin/env bash
d="$(dirname "$0")"
printf '%s\n' "$@" >"$d/argv"
jq -c '{is_error: false, structured_output: .}' "$d/answer.json"
SH
chmod +x "$dir/claude"
echo COMMON >"$dir/rules"
export YUKTA_FIXER_MCP=off YK_MODEL=sonnet YK_EFFORT=medium YK_RULES="$dir/rules" YK_SCHEMA='{}' YK_CLAUDE_BIN="$dir/claude"
# role -> schema of its answer
while read -r role schema; do
  cp "$here/fixtures/$schema/valid.json" "$dir/answer.json"
  YK_ROLE="$role" bash "$dogfood/scripts/claude.sh" <<<'{"prompt":"p"}' >"$dir/out.json" || fail "$role: claude.sh failed"
  jq -e .structured_output "$dir/out.json" >/dev/null || fail "$role: no structured output"
  jq .structured_output "$dir/out.json" >"$dir/answer-only.json"
  "$tk" validate-schema "$schema" "$dir/answer-only.json" >/dev/null || fail "$role: answer not valid for $schema"
  grep -q "^Role: ticket ${role//_/ }" "$dir/argv" || fail "$role: role file missing from the system prompt"
  grep -q '^COMMON' "$dir/argv" || fail "$role: common rules missing"
  cp "$dir/argv" "$dir/argv.1"
  YK_ROLE="$role" bash "$dogfood/scripts/claude.sh" <<<'{"prompt":"p"}' >/dev/null
  cmp -s "$dir/argv" "$dir/argv.1" || fail "$role: the system prompt changed between two calls (breaks the prompt cache)"
done <<'LIST'
architect ticket-spec
test_writer test-writer-result
implementer step-result
reviewer review-result
LIST
YK_ROLE=wizard bash "$dogfood/scripts/claude.sh" <<<'{"prompt":"p"}' >/dev/null 2>&1 && fail "unknown role must fail"
grep -qx medium "$dir/argv" || fail "medium effort not passed on"
echo "PASS ticket schemas and roles"
