#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Runs after the package is installed or upgraded. Creates the service user. Does not enable or
# start the service: review /etc/yukta/yukta.env first, then: systemctl enable --now yukta
# A service that is already running is restarted, so an upgrade takes effect.
set -e
if ! getent group yukta >/dev/null 2>&1; then
  groupadd --system yukta
fi
if ! getent passwd yukta >/dev/null 2>&1; then
  useradd --system --gid yukta --no-create-home --home-dir /var/lib/yukta --shell /usr/sbin/nologin yukta
fi
if [ -d /run/systemd/system ]; then
  systemctl daemon-reload >/dev/null 2>&1 || true
  # An upgrade replaces /usr/bin/yukta under a running process; restart it so the new version runs.
  # try-restart does nothing when the service is not running, so a fresh install stays stopped.
  systemctl try-restart yukta >/dev/null 2>&1 || true
fi
