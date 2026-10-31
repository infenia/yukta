// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchAllPages } from '@/lib/apiClient';
import type { SessionListItem } from '../types/session';

/** Sessions read per request, and most requests: 200 x 50 = 10 000 sessions. */
const PAGE_SIZE = 200;
const MAX_PAGES = 50;

/**
 * Every session summary. The server returns one page at a time (500 by default), so a longer
 * list would be cut off silently; this follows the cursor until the end.
 */
export async function getSessionSummaries(): Promise<SessionListItem[]> {
  return fetchAllPages<SessionListItem>(
    '/api/sessions/summaries',
    PAGE_SIZE,
    MAX_PAGES
  );
}
