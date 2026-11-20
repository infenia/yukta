// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api } from '@/lib/apiClient';
import type { AppNotification } from './api';

type Ref = Pick<
  AppNotification,
  'id' | 'sessionId' | 'workflowId' | 'executionId' | 'nodeId'
>;

/** The run view of a workflow: the run (`?exec=`) and its node (`?node=`) when the item names them. */
export function workflowHref(n: Ref): string | null {
  if (!n.sessionId || !n.workflowId) return null;
  const query = new URLSearchParams();
  if (n.executionId) query.set('exec', n.executionId);
  if (n.executionId && n.nodeId) query.set('node', n.nodeId);
  const qs = query.toString();
  return (
    api`/sessions/${n.sessionId}/workflow/${n.workflowId}` +
    (qs ? `?${qs}` : '')
  );
}

/** Where a notification leads: the run, else the workflow, else the session, else its inbox row. */
export function notificationHref(n: Ref): string {
  return (
    workflowHref(n) ??
    (n.sessionId ? api`/sessions/${n.sessionId}` : `/inbox?item=${n.id}`)
  );
}
