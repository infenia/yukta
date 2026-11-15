// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createContext, useContext } from 'react';
import type { AppNotification, NotificationSeverity } from './api';

/** A notification that just arrived, to be shown as a toast. */
export interface Toast {
  id: string;
  notification: AppNotification;
}

export interface NotificationsState {
  /** Everything not dismissed, newest change first. */
  items: AppNotification[];
  /** Active actions that wait for the caller. */
  actions: AppNotification[];
  /** Info notifications not read yet. */
  unread: AppNotification[];
  /** The highest severity of the actions and the unread info, or null. */
  highest: NotificationSeverity | null;
  /** False while the stream is down: the list is polled instead. */
  connected: boolean;
  toasts: Toast[];
  dismissToast: (id: string) => void;
  markRead: (ids?: string[]) => void;
}

const EMPTY: NotificationsState = {
  items: [],
  actions: [],
  unread: [],
  highest: null,
  connected: true,
  toasts: [],
  dismissToast: () => undefined,
  markRead: () => undefined,
};

export const NotificationsContext = createContext<NotificationsState>(EMPTY);

export const useNotifications = () => useContext(NotificationsContext);
