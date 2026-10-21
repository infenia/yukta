#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# After a release is published: comment "Released in <tag>" on every issue that a pull request in the
# release closed (Fixes #n), and close the milestone named like the tag, if there is one. The range is
# the previous published release to this one; the first release has no range and is skipped.
#
# Env: GH_TOKEN, REPO, TAG, RELEASE_URL
set -euo pipefail

owner=${REPO%%/*}
name=${REPO#*/}
previous=$(gh api "repos/$REPO/releases?per_page=100" |
  jq -r --arg t "$TAG" '[.[] | select(.draft | not) | select(.tag_name != $t)] | sort_by(.published_at) | last | .tag_name // empty')
if [ -z "$previous" ]; then
  echo "No earlier release; nothing to compare $TAG with."
else
  issues=$(
    gh api --paginate "repos/$REPO/compare/$previous...$TAG?per_page=100" --jq '.commits[].sha' |
      while read -r sha; do gh api "repos/$REPO/commits/$sha/pulls" --jq '.[].number'; done | sort -un |
      while read -r pr; do
        # shellcheck disable=SC2016 # GraphQL variables, not shell
        gh api graphql -F owner="$owner" -F name="$name" -F pr="$pr" -f query='
          query($owner: String!, $name: String!, $pr: Int!) {
            repository(owner: $owner, name: $name) { pullRequest(number: $pr) {
              closingIssuesReferences(first: 50) { nodes { number repository { nameWithOwner } } } } } }' \
          --jq '.data.repository.pullRequest.closingIssuesReferences.nodes[] | select(.repository.nameWithOwner == "'"$REPO"'") | .number'
      done | sort -un
  )
  for issue in $issues; do
    gh issue comment "$issue" -R "$REPO" --body "Released in [$TAG]($RELEASE_URL)." >/dev/null
    echo "Commented on #$issue"
  done
fi

milestone=$(gh api "repos/$REPO/milestones?state=open&per_page=100" | jq -r --arg t "$TAG" 'map(select(.title == $t)) | first | .number // empty')
if [ -n "$milestone" ]; then
  gh api -X PATCH "repos/$REPO/milestones/$milestone" -f state=closed >/dev/null
  echo "Closed milestone $TAG"
fi
