// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router';
import { getPreferences } from './api';
import { readDevicePrefs } from './devicePrefs';
import { useNotifications } from './NotificationsContext';
import { notificationHref } from './notificationLink';
import { plain } from './severity';

export const GENERIC_TITLE = 'Yukta needs your input';

/** Whether this browser can raise notifications at all. */
export const desktopSupported = () => typeof Notification !== 'undefined';

/**
 * Asks for the permission. Only call it from a click: a browser ignores a request made without a
 * user gesture. Resolves to the permission, or 'unsupported'.
 */
export async function requestDesktopPermission(): Promise<
  NotificationPermission | 'unsupported'
> {
  if (!desktopSupported()) return 'unsupported';
  return Notification.requestPermission();
}

/**
 * Raises an operating system notification for an item that arrives while the tab is not in view,
 * when the person's level for its category is DESKTOP and this device has the switch on. The
 * pop-up shows the title and the workflow, never the question's context, and has no buttons: a
 * click opens the item in the inbox. The tag stops the same item showing twice across tabs.
 */
export function useDesktopNotifications() {
  const { items, loaded } = useNotifications();
  const navigate = useNavigate();
  const { data: preferences } = useQuery({
    queryKey: ['notification-preferences'],
    queryFn: getPreferences,
  });
  const seen = useRef<Set<string> | null>(null);

  useEffect(() => {
    if (!loaded) return;
    if (seen.current === null) {
      // what was already there when the page opened is not news
      seen.current = new Set(items.map((i) => i.id));
      return;
    }
    const device = readDevicePrefs();
    for (const item of items) {
      if (seen.current.has(item.id)) continue;
      seen.current.add(item.id);
      if (
        item.read ||
        item.state !== 'ACTIVE' ||
        !device.desktop ||
        !desktopSupported() ||
        Notification.permission !== 'granted' ||
        document.visibilityState === 'visible' ||
        preferences?.levels[item.category] !== 'DESKTOP'
      ) {
        continue;
      }
      const pop = device.details
        ? new Notification(plain(item.title), {
            body: item.workflowId ? plain(item.workflowId) : undefined,
            tag: item.id,
          })
        : new Notification(GENERIC_TITLE, { tag: item.id });
      pop.onclick = () => {
        window.focus();
        navigate(notificationHref(item));
        pop.close();
      };
    }
  }, [items, loaded, preferences, navigate]);
}
