#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Clones or updates the dogfood checkout (https://github.com/infenia/yukta-dogfood: `yk` and its hooks) to the ref
# this repository pins in .dogfood-ref. Idempotent. Where: $YUKTA_DOGFOOD_HOME, else $DEV_HOME/dogfood
# ($YUKTA_DEV_HOME, else ${XDG_STATE_HOME:-~/.local/state}/yukta-dev).
set -euo pipefail

root=$(git rev-parse --show-toplevel)
ref=$(grep -v '^#' "$root/.dogfood-ref" | head -n1 | tr -d '[:space:]')
dev=${YUKTA_DEV_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/yukta-dev}
dir=${YUKTA_DOGFOOD_HOME:-$dev/dogfood}
url=${YUKTA_DOGFOOD_URL:-https://github.com/infenia/yukta-dogfood.git}

[ -n "$ref" ] || { echo "dogfood-setup: .dogfood-ref is empty" >&2; exit 1; }
[ -d "$dir/.git" ] || git clone -q "$url" "$dir"
git -C "$dir" fetch -q --tags origin "$ref" 2>/dev/null || git -C "$dir" fetch -q --tags origin
git -C "$dir" checkout -q --detach "$ref"
(cd "$dir" && bun install --frozen-lockfile >/dev/null)
echo "dogfood: $dir at $(git -C "$dir" rev-parse --short HEAD) ($ref); put it on PATH: export PATH=\"$dir:\$PATH\""
