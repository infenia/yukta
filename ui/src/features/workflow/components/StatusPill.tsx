// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { toStatusDisplay, type StatusTone } from '../lib/statusMapper';
import { cn } from '@/lib/utils';

const TONE_CLASSES: Record<StatusTone, { pill: string; dot: string }> = {
  muted: {
    pill: 'text-muted-foreground border-border bg-muted',
    dot: 'bg-muted-foreground',
  },
  primary: {
    pill: 'text-primary border-primary/30 bg-primary/10',
    dot: 'bg-primary',
  },
  attention: {
    pill: 'text-attention border-attention/30 bg-attention/10',
    dot: 'bg-attention',
  },
  success: {
    pill: 'text-success border-success/30 bg-success/10',
    dot: 'bg-success',
  },
  destructive: {
    pill: 'text-destructive border-destructive/30 bg-destructive/10',
    dot: 'bg-destructive',
  },
};

/**
 * The one way a run or node status is shown: a sentence-case label with an icon (or a live dot),
 * coloured by tone. The raw backend value stays in data-status and the title for agents and tests.
 */
export function StatusPill({
  status,
  scope = 'run',
  testId = 'status-chip',
  className,
}: {
  status: string | null | undefined;
  scope?: 'run' | 'node';
  testId?: string;
  className?: string;
}) {
  const { label, tone, icon, dashed } = toStatusDisplay(status, scope);
  const raw = status ?? 'NOT_RUN';
  return (
    <span
      data-testid={testId}
      data-status={raw}
      title={raw}
      className={cn(
        'text-label-sm inline-flex h-[22px] shrink-0 items-center gap-1.5 rounded-full border pr-2.5 pl-2 whitespace-nowrap',
        TONE_CLASSES[tone].pill,
        dashed && 'border-dashed bg-transparent',
        className
      )}
    >
      {icon ? (
        <span aria-hidden className="material-symbols-outlined text-[15px]!">
          {icon}
        </span>
      ) : (
        <span
          aria-hidden
          className={cn(
            'live-dot size-[7px] shrink-0 rounded-full',
            TONE_CLASSES[tone].dot
          )}
        />
      )}
      {label}
    </span>
  );
}
