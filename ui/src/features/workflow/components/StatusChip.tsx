// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { toExecStatusCategory } from '../lib/statusMapper';
import { cn } from '@/lib/utils';
import type { ExecStatusCategory } from '../types/workflow';

const CATEGORY_CLASSES: Record<
  ExecStatusCategory,
  { chip: string; dot: string }
> = {
  running: {
    chip: 'border-primary/40 bg-primary/10 text-primary',
    dot: 'bg-primary animate-pulse',
  },
  success: {
    chip: 'border-success/40 bg-success/10 text-success',
    dot: 'bg-success',
  },
  failure: { chip: 'border-error/40 bg-error/10 text-error', dot: 'bg-error' },
  stopped: {
    chip: 'border-outline-variant text-on-surface-variant',
    dot: 'bg-outline',
  },
  paused: {
    chip: 'border-warning/40 bg-warning/10 text-warning',
    dot: 'bg-warning',
  },
  pending: {
    chip: 'border-outline-variant text-on-surface-variant',
    dot: 'border border-outline',
  },
  unknown: {
    chip: 'border-outline-variant text-on-surface-variant',
    dot: 'border border-outline',
  },
};

/** Execution status chip: colored dot plus label, e.g. the RUNNING badge in the title row. */
export function StatusChip({ status }: { status: string | null }) {
  const category = toExecStatusCategory(status);
  const label = status ? status.replace(/_/g, ' ').toUpperCase() : 'NOT RUN';
  const { chip, dot } = CATEGORY_CLASSES[category];
  return (
    <span
      data-testid="status-chip"
      className={cn(
        'text-label-sm inline-flex items-center gap-2 border px-3 py-1 tracking-wider',
        chip
      )}
    >
      <span aria-hidden className={cn('size-2 shrink-0 rounded-full', dot)} />
      {label}
    </span>
  );
}
