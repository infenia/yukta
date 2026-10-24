#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Runs after the package is removed. Forgets the unit file that was removed. On purge (deb) it also
# deletes the service user and group; /var/lib/yukta is left alone, it holds the admin's data.
set -e
case "${1:-}" in
  remove | purge | 0)
    if [ -d /run/systemd/system ]; then
      systemctl daemon-reload >/dev/null 2>&1 || true
    fi
    ;;
esac
if [ "${1:-}" = purge ]; then
  userdel yukta >/dev/null 2>&1 || true
  groupdel yukta >/dev/null 2>&1 || true
fi
