// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { StateMessage } from '@/components/common/StateMessage';

/**
 * The body of a panel: a loading, error or empty message, or the content. Every dashboard panel
 * renders through it, so they all fail and wait the same way.
 */
export function PanelState({
  isPending,
  error,
  empty,
  emptyText,
  onRetry,
  children,
}: {
  isPending: boolean;
  error: Error | null;
  empty: boolean;
  emptyText: string;
  onRetry?: () => void;
  children: ReactNode;
}) {
  if (isPending) return <StateMessage compact tone="loading" title="Loading" />;
  if (error) {
    return (
      <StateMessage
        compact
        tone="error"
        title="Could not load"
        onRetry={onRetry}
      >
        {error.message}
      </StateMessage>
    );
  }
  if (empty) return <StateMessage compact title={emptyText} />;
  return <>{children}</>;
}
