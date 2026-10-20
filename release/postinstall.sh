#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Runs after the package is installed or upgraded. Creates the service user. Does not enable or
# start the service: review /etc/yukta/yukta.env first, then: systemctl enable --now yukta
set -e
if ! getent group yukta >/dev/null 2>&1; then
  groupadd --system yukta
fi
if ! getent passwd yukta >/dev/null 2>&1; then
  useradd --system --gid yukta --no-create-home --home-dir /var/lib/yukta --shell /usr/sbin/nologin yukta
fi
if [ -d /run/systemd/system ]; then
  systemctl daemon-reload >/dev/null 2>&1 || true
fi
