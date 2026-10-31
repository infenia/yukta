#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# After a release is published: replace the previously documented version with the new one in docs/
# and README.md (they pin it in install commands), then open a pull request with auto-merge on. It is a chore, which release-please hides, so it
# does not by itself call for another release.
# The old version is read from the install command in docs/quickstart.mdx.
#
# Env: GH_TOKEN (an App token: the PR must start CI), REPO, TAG
set -euo pipefail

new=${TAG#v}
old=$(sed -n 's#.*releases/download/v\([^/]*\)/install\.sh.*#\1#p' docs/quickstart.mdx | head -1)
if [ -z "$old" ] || [ "$old" = "$new" ]; then
  echo "Docs already pin $new (or no pin found); nothing to do."
  exit 0
fi

# Do not match inside a longer version (alpha.2 inside alpha.20). Lines about one specific release stay as
# they are: "alpha.2 and earlier ...", the known-issue notes (#773),
# the benchmark of "the released ..." server, and the changelog in docs/reference/releases.mdx (only its
# "Current release" line moves).
grep -rlF -- "$old" docs README.md | grep -v '^docs/reference/releases.mdx$' |
  xargs sed -i "/earlier\|#773\|against the released/! s/${old//./\\.}\([^0-9]\|\$\)/$new\1/g"
sed -i "s/^\(Current release: \*\*\`\)${old//./\\.}/\1$new/" docs/reference/releases.mdx
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
