// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';
import type { InputRequest } from '@/features/workflow/api/inputs';

export type NotificationKind = 'INFO' | 'ACTION';
export type NotificationSeverity = 'INFO' | 'WARN' | 'ERROR';

/** One item of the caller's inbox. The title and the body are untrusted text. */
export interface AppNotification {
  id: string;
  key: string;
  kind: NotificationKind;
  severity: NotificationSeverity;
  category: string;
  title: string;
  body: string | null;
  sessionId: string | null;
  workflowId: string | null;
  executionId: string | null;
  nodeId: string | null;
  inputRequestId: string | null;
  state: 'ACTIVE' | 'RESOLVED';
  resolution: string | null;
  count: number;
  seq: number;
  createdAt: string;
  updatedAt: string;
  read: boolean;
  dismissed: boolean;
  /** The input request of an action, so it can be answered without a second call. */
  input: InputRequest | null;
}

/** One frame of /api/notifications/stream; the type is in the data. */
export interface NotificationEvent {
  type: 'created' | 'updated' | 'resolved' | 'receipt' | 'reset';
  notification: AppNotification | null;
  notificationId: string | null;
  readAt: string | null;
  dismissedAt: string | null;
}

export const NOTIFICATIONS_STREAM = '/api/notifications/stream';

export const listNotifications = () =>
  fetchApi<AppNotification[]>('/api/notifications?size=200');

/** Marks the given notifications read, or every one when no ids are given. */
export const markNotificationsRead = (ids?: string[]) =>
  fetchApi<{ marked: number }>('/api/notifications/read', {
    method: 'POST',
    body: JSON.stringify(ids ? { ids } : { all: true }),
  });
