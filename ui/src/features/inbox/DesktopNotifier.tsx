// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useDesktopNotifications } from './useDesktopNotifications';

/** Mounted once inside the router: raises the desktop notifications. Renders nothing. */
export function DesktopNotifier() {
  useDesktopNotifications();
  return null;
}
