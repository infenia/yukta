#!/bin/sh
# Judges the uncommitted change of a git checkout (staged, unstaged and new files) against rules.
# Prints {"files":[...],"violations":[{"rule","file","message"}]} and exits 1 when a rule is broken.
# Usage: patch-guard.sh --dir D [--base REF] [--protected GLOB]... [--forbid REGEX]...
#                       [--no-test-removal] [--require-change]
set -eu
dir= base=HEAD prot= forbid= notest=0 reqchg=0
while [ $# -gt 0 ]; do
  case $1 in
    --dir) dir=$2; shift 2 ;;
    --base) base=$2; shift 2 ;;
    --protected) prot="$prot$2
"; shift 2 ;;
    --forbid) forbid="$forbid$2
"; shift 2 ;;
    --no-test-removal) notest=1; shift ;;
    --require-change) reqchg=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$dir" ] || { echo "--dir is required" >&2; exit 2; }
# a ref never starts with a dash, so it cannot read as an option
case $base in -*|*[!A-Za-z0-9_./~^@{}-]*|'') echo "base is not a git ref: $base" >&2; exit 2 ;; esac
cd "$dir"
g() { if command -v timeout >/dev/null; then timeout 30 git "$@"; else git "$@"; fi; }

{
  g diff --no-color --unified=0 "$base"
  # new files are untracked: their whole content counts as added
  g ls-files --others --exclude-standard | while IFS= read -r f; do
    g diff --no-color --unified=0 --no-index -- /dev/null "$f" || true
  done
} | PROT="$prot" FORBID="$forbid" NOTEST=$notest REQ=$reqchg awk '
function esc(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); return s }
function glob(g,  r, i, c) {
  for (i = 1; i <= length(g); i++) {
    c = substr(g, i, 1)
    if (c == "*" && substr(g, i + 1, 1) == "*") { r = r ".*"; i++ }
    else if (c == "*") r = r "[^/]*"
    else if (c == "?") r = r "[^/]"
    else if (c ~ /[.+(){}|^$\[\]\\]/) r = r "\\" c
    else r = r c
  }
  return "^" r "$"
}
function viol(rule, file, msg) {
  v = v (nv++ ? "," : "") "{\"rule\":\"" rule "\",\"file\":\"" esc(file) "\",\"message\":\"" esc(msg) "\"}"
}
BEGIN {
  np = split(ENVIRON["PROT"], P, "\n"); nf = split(ENVIRON["FORBID"], F, "\n")
  marker = "^[ \t]*(@Test|@ParameterizedTest|func Test[A-Za-z0-9_]*\\(|(it|test)\\(|def test_[A-Za-z0-9_]*\\()"
  testfile = "(^|/)(src/test/|tests?/|__tests__/)|Tests?\\.java$|_test\\.go$|\\.(test|spec)\\.[jt]sx?$|(^|/)test_[^/]*\\.py$"
}
/^--- / { name = ($0 == "--- /dev/null") ? "" : substr($0, 7); next }
/^\+\+\+ / {
  gone = ($0 == "+++ /dev/null"); cur = gone ? name : substr($0, 7)
  if (!(cur in seen)) { seen[cur] = 1; order[++n] = cur }
  del[cur] = gone; next
}
cur != "" && /^\+/ {
  line = substr($0, 2); if (line ~ marker) add[cur]++
  for (i = 1; i <= nf; i++) if (F[i] != "" && line ~ F[i]) viol("forbidPatterns", cur, "added line matches " F[i])
  next
}
cur != "" && /^-/ { if (substr($0, 2) ~ marker) rem[cur]++ }
END {
  for (k = 1; k <= n; k++) {
    f = order[k]; files = files (k > 1 ? "," : "") "\"" esc(f) "\""
    for (i = 1; i <= np; i++) if (P[i] != "" && f ~ glob(P[i])) viol("protectedPaths", f, "protected path changed")
    if (ENVIRON["NOTEST"] == 1 && f ~ testfile) {
      if (del[f]) viol("noTestRemoval", f, "test file deleted")
      else if (rem[f] > add[f]) viol("noTestRemoval", f, (rem[f] - add[f]) " test(s) removed")
    }
  }
  if (ENVIRON["REQ"] == 1 && n == 0) viol("requireChange", "", "the change is empty")
  print "{\"files\":[" files "],\"violations\":[" v "]}"
  exit nv ? 1 : 0
}'
