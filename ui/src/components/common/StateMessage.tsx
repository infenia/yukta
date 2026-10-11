// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';

/** Centered loading / empty / error block with an optional retry. */
export function StateMessage({
  tone = 'neutral',
  title,
  children,
  onRetry,
}: {
  tone?: 'neutral' | 'error';
  title: string;
  children?: ReactNode;
  onRetry?: () => void;
}) {
  return (
    <div
      role={tone === 'error' ? 'alert' : undefined}
      className="border-outline-variant flex flex-col items-center gap-2 border border-dashed px-6 py-12 text-center"
    >
      <p
        className={`text-label-md ${tone === 'error' ? 'text-error' : 'text-on-surface'}`}
      >
        {title}
      </p>
      {children && (
        <p className="text-body-sm text-on-surface-variant">{children}</p>
      )}
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          Retry
        </Button>
      )}
    </div>
  );
}
