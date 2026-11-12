#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Keeps the .github guides and the workflows in step, both ways:
#   1. every file in .github/workflows/ has a heading in WORKFLOWS.md;
#   2. every `name.yml` a .github/*.md mentions is a workflow, a file of .github/ or a composite action's action.yml.
set -euo pipefail
cd "$(dirname "$0")/.."   # .github

status=0
tick='`'
for file in workflows/*.yml; do
  name="${file#workflows/}"
  grep -qE "^#+ .*$tick$name$tick" WORKFLOWS.md || { echo "::error file=.github/WORKFLOWS.md::no section for workflows/$name"; status=1; }
done

while IFS=: read -r doc name; do
  [ -e "workflows/$name" ] || [ -e "$name" ] || compgen -G "actions/*/$name" >/dev/null ||
    { echo "::error file=.github/$doc::names $name, which is not a workflow"; status=1; }
done < <(grep -oHE '[A-Za-z0-9_-]+\.yml' ./*.md | sed 's#^\./##' | sort -u)
exit "$status"
