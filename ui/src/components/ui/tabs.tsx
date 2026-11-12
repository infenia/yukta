// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { KeyboardEvent } from 'react';
import { cn } from '@/lib/utils';

export interface TabItem<T extends string> {
  id: T;
  label: string;
  count?: number;
}

/**
 * Underline tabs with an optional count, left-aligned under a page header. With `idPrefix` each tab
 * gets the id `<idPrefix>-tab-<id>` and points at the panel `<idPrefix>-tabpanel`.
 */
export function Tabs<T extends string>({
  items,
  value,
  onChange,
  ariaLabel,
  idPrefix,
}: {
  items: readonly TabItem<T>[];
  value: T;
  onChange: (id: T) => void;
  ariaLabel: string;
  idPrefix?: string;
}) {
  // arrow keys move between tabs (roving focus), Home and End jump to the ends
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const step = { ArrowRight: 1, ArrowLeft: -1 }[event.key];
    const index = items.findIndex((item) => item.id === value);
    const next =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? items.length - 1
          : step === undefined
            ? -1
            : (index + step + items.length) % items.length;
    if (next < 0) return;
    event.preventDefault();
    onChange(items[next].id);
    const buttons =
      event.currentTarget.querySelectorAll<HTMLElement>('[role=tab]');
    buttons[next]?.focus();
  };
  return (
    <div
      role="tablist"
      aria-label={ariaLabel}
      onKeyDown={onKeyDown}
      className="border-border flex gap-5 border-b"
    >
      {items.map(({ id, label, count }) => (
        <button
          key={id}
          id={idPrefix && `${idPrefix}-tab-${id}`}
          type="button"
          role="tab"
          aria-selected={value === id}
          aria-controls={idPrefix && `${idPrefix}-tabpanel`}
          tabIndex={value === id ? 0 : -1}
          onClick={() => onChange(id)}
          className={cn(
            'text-label-md -mb-px border-b-2 py-2',
            value === id
              ? 'border-primary text-on-surface'
              : 'text-on-surface-variant hover:text-on-surface border-transparent'
          )}
        >
          {label}
          {count !== undefined && (
            <span className="text-body-sm text-muted-foreground ml-1.5 tabular-nums">
              {count}
            </span>
          )}
        </button>
      ))}
    </div>
  );
}
