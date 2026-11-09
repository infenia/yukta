// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { StateMessage } from '@/components/common/StateMessage';
import { useNow } from '@/features/control/dashboard/useNow';
import { formatRelative } from '@/features/workflow/lib/timeFormat';

/** The parts of a query this notice reads. */
interface Kept {
  error: Error | null;
  data: unknown;
  dataUpdatedAt: number;
  refetch: () => unknown;
}

/**
 * Says the page shows what an earlier fetch returned, when a refresh failed but data is kept; shown
 * above that data instead of replacing it with an error.
 */
export function StaleNotice({ query }: { query: Kept }) {
  const now = useNow();
  if (!query.error || query.data === undefined) return null;
  const when = formatRelative(new Date(query.dataUpdatedAt).toISOString(), now);
  return (
    <StateMessage
      compact
      icon="history"
      title={`Showing what was there ${when}`}
      onRetry={() => void query.refetch()}
    >
      The server is not answering; this may be out of date.
    </StateMessage>
  );
}
