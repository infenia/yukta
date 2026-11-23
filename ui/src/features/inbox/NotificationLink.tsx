// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ComponentProps } from 'react';
import { Link } from 'react-router';
import type { AppNotification } from './api';
import { useNotifications } from './NotificationsContext';
import { notificationHref } from './notificationHref';

/** A link to where a notification belongs; following it marks the notification read. */
export function NotificationLink({
  item,
  onClick,
  ...props
}: Omit<ComponentProps<typeof Link>, 'to'> & { item: AppNotification }) {
  const { markRead } = useNotifications();
  return (
    <Link
      to={notificationHref(item)}
      onClick={(e) => {
        if (!item.read) markRead([item.id]);
        onClick?.(e);
      }}
      {...props}
    />
  );
}
