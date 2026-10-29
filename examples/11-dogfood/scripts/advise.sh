#!/usr/bin/env bash
# advise.sh [--base REF] [--no-model]: recommends a test tier (CLAUDE.md "Test Execution Policy") for the current changes and runs nothing.
#
# The floor is deterministic, from the changed paths. The decision model (decisions.json) sees only a digest, one line per file
# (path, lines added and removed, kind of file; never a diff) and says what kind of change it is. Its answer can only WIDEN the tier:
#   public-contract -> at least 2, build-or-config -> at least 3, unsure (below minProbability) -> one tier more than the floor.
# No model, an error or an oversized digest: the floor stands. docs-or-comment-only / test-only / internal-change never narrow it.
#
#   tier 0 compile  1 tests of the changed classes  2 tests of the package  3 whole module  4 full check
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$here/scripts/_lib.sh"
root="$(git -C "$here" rev-parse --show-toplevel)"
cd "$root"

base=origin/main model=1 min="${YUKTA_ADVISE_MIN_PROBABILITY:-0.6}"
while (($#)); do
  case "$1" in
    --base) base="${2:?--base needs a ref}"; shift 2 ;;
    --no-model) model=0; shift ;;
    *) echo "usage: advise.sh [--base REF] [--no-model]" >&2; exit 2 ;;
  esac
done

mb="$(git merge-base HEAD "$base")" || { echo "advise.sh: no merge base with $base" >&2; exit 1; }
mapfile -t files < <( (git diff --name-only "$mb"; git ls-files --others --exclude-standard) | sort -u)
((${#files[@]})) || { echo "advice: nothing changed, nothing to check"; exit 0; }

# ---- the floor, and the digest ----
tier=-1 digest=""
declare -A stat=()
while IFS=$'\t' read -r a d f; do stat["$f"]="+$a -$d"; done < <(git diff --numstat "$mb" | grep -v '^-' || true)
for f in "${files[@]}"; do
  case "$f" in
    build-logic/* | gradle/* | config/* | settings.gradle.kts | build.gradle.kts | gradle.properties) k=build; t=4 ;;
    plugin-api/* | messaging/*) k=shared; t=3 ;;
    *.md | docs/*) k=docs; t=0 ;;
    */src/test/* | ui/src/test/*) k=test; t=1 ;;
    *) k=code; t=1 ;;
  esac
  ((t > tier)) && tier=$t
  digest+="$f ${stat[$f]:-+new} $k"$'\n'
done
((tier >= 1 && tier < 3 && ${#files[@]} > 15)) && tier=3 || { ((tier >= 1 && tier < 2 && ${#files[@]} > 5)) && tier=2; }
floor=$tier why="paths"

# ---- the model can only widen ----
if ((model)); then
  crit='{"docs-or-comment-only":"Only documentation or comments changed; no behaviour.","test-only":"Only tests changed.","internal-change":"Code changed behind existing signatures: private or package-private members, a local rename, an implementation.","public-contract":"A public type, method signature, REST or MCP schema, configuration key or serialized format changed, so dependants can break.","build-or-config":"The build, dependencies, CI or configuration changed."}'
  if ans="$(decide advise "$(head -c 900 <<<"${#files[@]} changed files"$'\n'"$digest")" "What kind of change is this?" "$crit")"; then
    read -r label p <<<"$ans"
    why="model: $label ($p)"
    if awk -v p="$p" -v m="$min" 'BEGIN { exit !(p < m) }'; then
      tier=$((tier + 1)); why+=", unsure: one tier more"
    else
      case "$label" in
        public-contract) ((tier < 2)) && tier=2 ;;
        build-or-config) ((tier < 3)) && tier=3 ;;
      esac
    fi
  else
    why="no model answer, the floor stands"
  fi
fi
((tier > 4)) && tier=4
echo "advice: tier $tier (floor $floor from ${#files[@]} changed files; $why). Advice only, it never narrows the floor; --no-model gives the floor alone."
