#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Reports drift between docs/contributing/core-reference.mdx and the code.
#
#   core/scripts/check-core-reference.sh            check; exit 1 on any problem
#   core/scripts/check-core-reference.sh --stamp    record HEAD and today's date as "verified"
#
# Checks: cited classes exist, every yukta.* setting/meter string in core/src/main is on the
# page, feature IDs/status are consistent, retired IDs are not reused, in-page links resolve.
set -u
cd "$(dirname "$0")/../.."
DOC=docs/contributing/core-reference.mdx
[ -f "$DOC" ] || { echo "missing $DOC" >&2; exit 2; }

if [ "${1:-}" = "--stamp" ]; then
  commit=$(git rev-parse --short HEAD)
  today=$(date +%F)
  sed -i -E \
    -e "s|^(verifiedCommit: \")[^\"]*|\1${commit}|" \
    -e "s|^(verifiedDate: \")[^\"]*|\1${today}|" \
    -e "s|^(Last verified against commit \`)[^\`]*(\` on )[0-9-]*|\1${commit}\2${today}|" "$DOC"
  echo "stamped: commit ${commit}, date ${today}"
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
errors=0
fail() { echo "  FAIL: $*"; errors=$((errors + 1)); }

# Names that look like classes but are JDK, Reactor or library types, not part of this repository.
external='Mono Flux Runnable Sinks IllegalArgumentException'

# prose.mdx: the page without fenced code blocks (examples and the card template are not content)
awk '/^```/ { fence = !fence; next } !fence' "$DOC" > "$tmp/prose.mdx"

echo "[1/5] cited classes exist"
grep -rhoE '\b(class|record|interface|enum|@interface) [A-Z][A-Za-z0-9_]*' --include=*.java \
  core plugin-api plus plugins messaging web mcp 2>/dev/null \
  | awk '{print $2}' | sort -u > "$tmp/classes"
# a class is a backticked CamelCase word (at least two humps), e.g. `ExecutionScheduler`
grep -oP '`\K[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+(?=`)' "$tmp/prose.mdx" | sort -u > "$tmp/cited"
for e in $external; do sed -i "/^${e}\$/d" "$tmp/cited"; done
while read -r c; do
  grep -qx "$c" "$tmp/classes" || fail "class '$c' is cited but not found in the source tree"
done < "$tmp/cited"
echo "  $(wc -l < "$tmp/cited") classes checked"

echo "[2/5] settings and meters in core/src/main appear on the page"
grep -rhoE '"yukta\.[A-Za-z0-9_.-]+"|\$\{yukta\.[A-Za-z0-9_.-]+' --include=*.java core/src/main \
  | sed -E 's/^"//; s/"$//; s/^\$\{//' | sort -u > "$tmp/tokens"
while read -r t; do
  grep -qF -- "$t" "$DOC" || fail "'$t' is used in core/src/main but not documented"
done < "$tmp/tokens"
echo "  $(wc -l < "$tmp/tokens") strings checked"

echo "[3/5] feature cards are consistent"
# A card is "### GROUP-NN: name" whose next non-blank line is "**Status:** <status>".
awk '
  function flush() { if (id != "") print "CARD " id " " (st == "" ? "-" : st); id = ""; st = ""; seen = 0 }
  /^### / {
    flush()
    if ($0 ~ /^### [A-Z][A-Za-z]*-[0-9]+[: ]/) {
      id = $2
      if (id !~ /:$/) print "BADHEAD " id " " $0
      sub(/:$/, "", id)
    }
    next
  }
  id != "" && !seen && NF > 0 { seen = 1; if ($0 ~ /^\*\*Status:\*\*/) st = $2; next }
  /^## / { flush() }
  END { flush() }' "$tmp/prose.mdx" > "$tmp/features"
for d in $(awk '$1=="CARD"{print $2}' "$tmp/features" | sort | uniq -d); do fail "duplicate feature ID $d"; done
while read -r kind id val; do
  case "$kind" in
    BADHEAD) fail "$id heading must read '### GROUP-NN: name'";;
    CARD)
      echo "$id" | grep -qE '^[A-Z]{3}-[0-9]{2}$' || fail "$id does not match GROUP-NN"
      case "$val" in
        stable|optional|partial|planned) ;;
        -) fail "$id has no '**Status:**' line right after its heading";;
        *) fail "$id has invalid status '$val'";;
      esac;;
  esac
done < "$tmp/features"
cards=$(grep -c '^CARD' "$tmp/features")
statuses=$(grep -c '^\*\*Status:\*\*' "$tmp/prose.mdx")
[ "$cards" -eq "$statuses" ] || fail "$statuses Status lines but $cards feature cards"
echo "  $cards features checked"

echo "[4/5] retired IDs are not reused"
retired=$(grep -oP '\{/\* retired-ids: \K.*?(?= \*/\})' "$tmp/prose.mdx" | tr -d ' ' | tr ',' '\n' | grep -E '^[A-Z]{3}-[0-9]{2}$' || true)
for r in $retired; do
  awk '$1=="CARD"{print $2}' "$tmp/features" | grep -qx "$r" && fail "retired ID $r is in use"
done

echo "[5/5] in-page links resolve"
# heading slugs as the documentation site builds them: lowercase, punctuation dropped, spaces to hyphens
LC_ALL=C awk '
  /^##+ / {
    t = $0; sub(/^#+ /, "", t); t = tolower(t); gsub(/`/, "", t)
    gsub(/[^a-z0-9_ /-]/, "", t); gsub(/ /, "-", t); print t
  }' "$tmp/prose.mdx" | sort -u > "$tmp/ids"
# links are read outside inline code, so examples in backticks are not checked
sed -E 's/`[^`]*`//g' "$tmp/prose.mdx" | grep -oP '\]\(#\K[^)]+' | sort -u > "$tmp/links"
while read -r l; do
  grep -qx -- "$l" "$tmp/ids" || fail "link #$l has no target heading"
done < "$tmp/links"
echo "  $(wc -l < "$tmp/links") links checked"

echo
if [ "$errors" -eq 0 ]; then
  echo "OK: core-reference.mdx matches the code (run with --stamp to record this)."
else
  echo "$errors problem(s). Fix the page (see section 'Maintaining this document') and re-run."
  exit 1
fi
