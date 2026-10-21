#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Helpers for adding issues to the Yukta Roadmap project and setting its fields. Source it:
#   . .github/scripts/project-lib.sh
#   project_enabled && project_add_item "$issue_url" Status=Triage Kind="CI failure" Priority=P1
#
# Env: PROJECT_OWNER, PROJECT_NUMBER and PROJECT_TOKEN (a GitHub App token that can write org
# projects; GITHUB_TOKEN cannot). When any is empty the project is off and project_enabled is
# false, so callers keep working before the project and the App exist (see .github/PROJECT.md).
# Fields and options are looked up by name, so no ids are hard-coded; an unknown field or option
# is reported and skipped, never fatal, because the issue itself was already filed.

# Fields that are not text, by name; single-select fields are recognised by their options.
_project_numbers=" Impact Effort "
_project_dates=" Start date Target date "
_project_fields=""
_project_id=""

project_enabled() {
  [ -n "${PROJECT_OWNER:-}" ] && [ -n "${PROJECT_NUMBER:-}" ] && [ -n "${PROJECT_TOKEN:-}" ]
}

_project_gh() {
  GH_TOKEN="$PROJECT_TOKEN" gh "$@"
}

_project_load() {
  [ -n "$_project_id" ] && return 0
  _project_id=$(_project_gh project view "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json | jq -r .id)
  _project_fields=$(_project_gh project field-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" \
    --limit 100 --format json)
}

# project_add_item <issue or PR url> [Field=Value ...]
# Adds the item (a no-op if it is already there) and sets each field. Prints the item id.
project_add_item() {
  local url=$1 item assignment name value field field_id option
  shift
  _project_load
  item=$(_project_gh project item-add "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --url "$url" \
    --format json | jq -r .id)
  for assignment in "$@"; do
    name=${assignment%%=*}
    value=${assignment#*=}
    [ -n "$value" ] || continue
    field=$(jq -c --arg n "$name" '.fields | map(select(.name == $n)) | first // empty' <<<"$_project_fields")
    if [ -z "$field" ]; then
      echo "project: no field \"$name\"; skipped" >&2
      continue
    fi
    field_id=$(jq -r .id <<<"$field")
    if jq -e '.options' <<<"$field" >/dev/null; then
      option=$(jq -r --arg v "$value" '.options | map(select(.name == $v)) | first | .id // empty' <<<"$field")
      if [ -z "$option" ]; then
        echo "project: field \"$name\" has no option \"$value\"; skipped" >&2
        continue
      fi
      _project_gh project item-edit --id "$item" --project-id "$_project_id" --field-id "$field_id" \
        --single-select-option-id "$option" >/dev/null
    elif [[ $_project_numbers == *" $name "* ]]; then
      _project_gh project item-edit --id "$item" --project-id "$_project_id" --field-id "$field_id" \
        --number "$value" >/dev/null
    elif [[ $_project_dates == *" $name "* ]]; then
      _project_gh project item-edit --id "$item" --project-id "$_project_id" --field-id "$field_id" \
        --date "$value" >/dev/null
    else
      _project_gh project item-edit --id "$item" --project-id "$_project_id" --field-id "$field_id" \
        --text "$value" >/dev/null
    fi
  done
  echo "$item"
}

# issue_set_type <owner/repo> <issue number> <type name>
# Sets the issue type (Bug, Feature, Task, Epic) with the caller's GH_TOKEN; needs issues: write.
issue_set_type() {
  gh api -X PATCH "repos/$1/issues/$2" -f type="$3" >/dev/null ||
    echo "warning: could not set the type of #$2 to $3" >&2
}
