#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Runs before the package is removed. Stops the service on removal, not on upgrade.
set -e
case "${1:-}" in
  remove | 0)
    if [ -d /run/systemd/system ]; then
      systemctl stop yukta >/dev/null 2>&1 || true
      systemctl disable yukta >/dev/null 2>&1 || true
    fi
    ;;
esac
