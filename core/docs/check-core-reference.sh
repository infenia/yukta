#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Reports drift between core/docs/core-module-reference.html and the code.
#
#   core/docs/check-core-reference.sh            check; exit 1 on any problem
#   core/docs/check-core-reference.sh --stamp    record HEAD and today's date as "verified"
#
# Checks: cited classes exist, every yukta.* setting/meter string in core/src/main is on the
# page, feature IDs/status/badges are consistent, retired IDs are not reused, in-page links resolve.
set -u
cd "$(dirname "$0")/../.."
DOC=core/docs/core-module-reference.html
[ -f "$DOC" ] || { echo "missing $DOC" >&2; exit 2; }

if [ "${1:-}" = "--stamp" ]; then
  commit=$(git rev-parse --short HEAD)
  today=$(date +%F)
  sed -i -E \
    -e "s|(name=\"doc-verified-commit\" content=\")[^\"]*|\1${commit}|" \
    -e "s|(name=\"doc-verified-date\" content=\")[^\"]*|\1${today}|" "$DOC"
  echo "stamped: commit ${commit}, date ${today}"
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
errors=0
fail() { echo "  FAIL: $*"; errors=$((errors + 1)); }

# the feature template, <style> and <script> are not content
sed -e '/^<template id="feature-template">/,/^<\/template>/d' \
    -e '/^<style>/,/^<\/style>/d' -e '/^<script>/,/^<\/script>/d' "$DOC" > "$tmp/doc.html"

echo "[1/5] cited classes exist"
grep -rhoE '\b(class|record|interface|enum|@interface) [A-Z][A-Za-z0-9_]*' --include=*.java \
  core plugin-api workers persistence-postgres plugins messaging web mcp 2>/dev/null \
  | awk '{print $2}' | sort -u > "$tmp/classes"
grep -oP 'class="cls">\K[A-Za-z0-9_]+(?=<)' "$tmp/doc.html" | sort -u > "$tmp/cited"
while read -r c; do
  grep -qx "$c" "$tmp/classes" || fail "class '$c' is cited but not found in the source tree"
done < "$tmp/cited"
echo "  $(wc -l < "$tmp/cited") classes checked"

echo "[2/5] settings and meters in core/src/main appear on the page"
grep -rhoE '"yukta\.[A-Za-z0-9_.-]+"|\$\{yukta\.[A-Za-z0-9_.-]+' --include=*.java core/src/main \
  | sed -E 's/^"//; s/"$//; s/^\$\{//' | sort -u > "$tmp/tokens"
while read -r t; do
  grep -qF -- "$t" "$tmp/doc.html" || fail "'$t' is used in core/src/main but not documented"
done < "$tmp/tokens"
echo "  $(wc -l < "$tmp/tokens") strings checked"

echo "[3/5] feature cards are consistent"
awk '
  /^<article class="feature"/ {
    id=""; st=""
    if (match($0, /id="[^"]+"/)) id=substr($0, RSTART+4, RLENGTH-5)
    if (match($0, /data-status="[^"]+"/)) st=substr($0, RSTART+13, RLENGTH-14)
    pending=1; print "ARTICLE " id " " st; next
  }
  pending && /class="badge / {
    if (match($0, /class="badge [a-z]+"/)) b=substr($0, RSTART+13, RLENGTH-14)
    print "BADGE " id " " b; pending=0
  }' "$tmp/doc.html" > "$tmp/features"
for d in $(awk '$1=="ARTICLE"{print $2}' "$tmp/features" | sort | uniq -d); do fail "duplicate feature ID $d"; done
while read -r kind id val; do
  if [ "$kind" = "ARTICLE" ]; then
    [ -n "$id" ] || fail "a feature card has no id"
    case "$val" in stable|optional|partial|planned) ;; *) fail "$id has invalid data-status '$val'";; esac
    echo "$id" | grep -qE '^[A-Z]{3}-[0-9]{2}$' || fail "$id does not match GROUP-NN"
    grep -q "^BADGE $id " "$tmp/features" || fail "$id has no badge"
  fi
done < "$tmp/features" 2>/dev/null || true
awk '
  $1=="ARTICLE"{s[$2]=$3}
  $1=="BADGE"{ if (s[$2] != $3) printf "  FAIL: %s status %s but badge %s\n", $2, s[$2], $3 }' "$tmp/features" \
  | tee "$tmp/badge-errors"
errors=$((errors + $(wc -l < "$tmp/badge-errors")))
echo "  $(grep -c '^ARTICLE' "$tmp/features") features checked"

echo "[4/5] retired IDs are not reused"
retired=$(grep -oP '<!-- retired-ids: \K[^>]*(?= -->)' "$tmp/doc.html" | tr -d ' ' | tr ',' '\n' | grep -E '^[A-Z]{3}-[0-9]{2}$' || true)
for r in $retired; do
  awk '$1=="ARTICLE"{print $2}' "$tmp/features" | grep -qx "$r" && fail "retired ID $r is in use"
done

echo "[5/5] in-page links resolve"
grep -oP 'id="\K[^"]+' "$tmp/doc.html" | sort -u > "$tmp/ids"
grep -oP 'href="#\K[^"]+' "$tmp/doc.html" | sort -u > "$tmp/links"
while read -r l; do
  grep -qx "$l" "$tmp/ids" || fail "link #$l has no target"
done < "$tmp/links"
echo "  $(wc -l < "$tmp/links") links checked"

echo
if [ "$errors" -eq 0 ]; then
  echo "OK: core-module-reference.html matches the code (run with --stamp to record this)."
else
  echo "$errors problem(s). Fix the page (see section 'Maintaining this document') and re-run."
  exit 1
fi
