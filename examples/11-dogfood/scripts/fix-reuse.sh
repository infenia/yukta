#!/usr/bin/env bash
# The free fix of the `reuse` stage: gives every file that `reuse lint` reports without licensing information the project's header
# (Apache-2.0, Infenia Private Limited). A file type reuse cannot comment gets a sidecar `<file>.license`. Run in the repository root.
set -euo pipefail
year="${YUKTA_YEAR:-$(date +%Y)}"
mapfile -t files < <(reuse lint --json 2>/dev/null | jq -r '[.non_compliant.missing_licensing_info[]?, .non_compliant.missing_copyright_info[]?] | unique[]')
((${#files[@]})) || exit 0
reuse annotate --license Apache-2.0 --copyright "Infenia Private Limited" --year "$year" --merge-copyrights --fallback-dot-license -- "${files[@]}"
