#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# After a release is published: replace the previously documented version with the new one in docs/
# and README.md (they pin it in install commands), then open a pull request with auto-merge on. It is a chore, which release-please hides, so it
# does not by itself call for another release.
# The old version is read from the install command in docs/get-started/install.mdx.
#
# Env: GH_TOKEN (an App token: the PR must start CI), REPO, TAG; DRY_RUN=1 edits the files and stops.
set -euo pipefail

new=${TAG#v}
old=$(sed -n 's#.*releases/download/v\([^/]*\)/install\.sh.*#\1#p' docs/get-started/install.mdx | head -1)
if [ -z "$old" ] || [ "$old" = "$new" ]; then
  echo "Docs already pin $new (or no pin found); nothing to do."
  exit 0
fi

# Move only the pins of "the latest release", each matched by its own pattern. Statements about one
# specific release ("from 0.1.0-alpha.4", "0.1.0-alpha.3 and earlier") are history and are never touched,
# and neither is the changelog in docs/reference/releases.mdx beyond its "Current release" line.
# A new place that pins the latest release must get a pattern here.
o=${old//./\\.}
files=$(grep -rlF -- "$old" docs README.md | grep -Ev '^docs/reference/(whats-new|migration)-' || true)
if [ -n "$files" ]; then
  # shellcheck disable=SC2086
  sed -i \
    -e "s#releases/download/v$o/#releases/download/v$new/#g" \
    -e "s#ghcr\.io/infenia/yukta:$o\([^0-9.]\|\$\)#ghcr.io/infenia/yukta:$new\1#g" \
    -e "s#\"version\" : \"$o\"#\"version\" : \"$new\"#g" \
    -e "s#^\(Current release: \*\*\`\)$o#\1$new#" \
    -e "s#^\(| \`\)$o\(\` | Never | Exactly this release\)#\1$new\2#" \
    -e "s#\(Yukta is in alpha (\`\)$o\(\`)\)#\1$new\2#" \
    $files
fi
if git diff --quiet; then
  echo "No pinned version found to move; nothing to do."
  exit 0
fi
[ -z "${DRY_RUN:-}" ] || { git diff --stat; exit 0; }
branch="docs/install-$new"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git checkout -b "$branch"
git commit -qam "chore(docs): install the $new release"
git push "https://x-access-token:$GH_TOKEN@github.com/$REPO.git" "HEAD:refs/heads/$branch"
pr=$(gh pr create -R "$REPO" --base main --head "$branch" \
  --title "chore(docs): install the $new release" \
  --body "Points the install commands at [$TAG](https://github.com/$REPO/releases/tag/$TAG). Opened by the release follow-up workflow.")
gh pr merge "$pr" -R "$REPO" --auto --squash
