// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { toStatusDisplay } from '../../workflow/lib/statusMapper';
import type { SessionListItem } from '../types/session';

/** How the Last run filter names a session's last run. */
export const lastRunLabel = (session: SessionListItem) =>
  session.lastRun ? toStatusDisplay(session.lastRun.status).label : 'Not run';

/** Sessions that hold supervisor workflows and other machinery (tag yukta.internal). */
export const isSystemSession = (session: SessionListItem) =>
  session.tags.includes('yukta.internal');

/**
 * Sessions that carry every selected tag, were started by one of the selected initiators, and
 * whose last run is one of the selected kinds; system sessions only when asked for.
 */
export function filterSessions(
  sessions: SessionListItem[],
  { tags, initiators, lastRuns, system }: SessionFilters
): SessionListItem[] {
  return sessions.filter(
    (session) =>
      (system || !isSystemSession(session)) &&
      tags.every((tag) => session.tags.includes(tag)) &&
      (initiators.length === 0 || initiators.includes(session.initiator)) &&
      (lastRuns.length === 0 || lastRuns.includes(lastRunLabel(session)))
  );
}

/** The selected Tag (all must match) and Initiator (any may match) values. */
export interface SessionFilters {
  tags: string[];
  initiators: string[];
  /** Labels of the last run's status ("Failed", "Running"…), or "Not run". */
  lastRuns: string[];
  /** Show the system (internal) sessions too. */
  system: boolean;
}
