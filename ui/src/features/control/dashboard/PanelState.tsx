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
  if (isPending) {
    return (
      <div role="status" aria-busy="true" className="flex flex-col gap-2 py-2">
        <span className="sr-only">Loading</span>
        {['w-full', 'w-4/5', 'w-3/5'].map((width) => (
          <div
            key={width}
            aria-hidden="true"
            className={`${width} h-4 rounded bg-muted motion-safe:animate-pulse`}
          />
        ))}
      </div>
    );
  }
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
