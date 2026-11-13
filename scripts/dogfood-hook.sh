#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Claude Code hook entry (.claude/settings.json): runs the hook of the dogfood checkout (see dogfood-setup.sh) with the event name.
# Silent no-op when the checkout is not there, so a fresh clone is not broken by it.
dev=${YUKTA_DEV_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/yukta-dev}
hook=${YUKTA_DOGFOOD_HOME:-$dev/dogfood}/hooks/yk-hook.ts
[ -f "$hook" ] || exit 0
exec bun "$hook" "$@"
