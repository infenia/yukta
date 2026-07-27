// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { toExecStatusCategory } from '../lib/statusMapper';
import { cn } from '@/lib/utils';
import type { ExecStatusCategory } from '../types/workflow';

const CATEGORY_CLASSES: Record<ExecStatusCategory, string> = {
  running: 'border-primary text-primary',
  success: 'border-tertiary text-tertiary',
  failure: 'border-error text-error',
  stopped: 'border-outline text-on-surface-variant',
  paused: 'border-outline text-on-surface-variant',
  pending: 'border-outline-variant text-on-surface-variant',
  unknown: 'border-outline-variant text-on-surface-variant',
};

/** Outlined execution status chip, e.g. the RUNNING badge in the title row. */
export function StatusChip({ status }: { status: string | null }) {
  const category = toExecStatusCategory(status);
  const label = status ? status.replace(/_/g, ' ').toUpperCase() : 'NOT RUN';
  return (
    <span
      data-testid="status-chip"
      className={cn(
        'inline-flex items-center border px-3 py-1 text-xs font-semibold tracking-wider',
        CATEGORY_CLASSES[category]
      )}
    >
      {label}
    </span>
  );
}
