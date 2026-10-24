// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { CSSProperties, ReactNode } from 'react';
import { cn } from '@/lib/utils';

export type CalloutKind = 'info' | 'success' | 'warning' | 'danger';

/* As the docs callouts: one treatment for every kind, a light tint, a matching hairline and a
   coloured icon. Info is blue, success green, warning the logo's orange, danger destructive. */
const KINDS: Record<CalloutKind, { color: string; icon: string }> = {
  info: { color: 'var(--primary)', icon: 'info' },
  success: { color: 'var(--success)', icon: 'check_circle' },
  warning: { color: 'var(--attention)', icon: 'warning' },
  danger: { color: 'var(--destructive)', icon: 'error' },
};

/** A tinted notice: errors, warnings, confirmations and notes. */
export function Callout({
  kind = 'info',
  title,
  children,
  compact = false,
  className,
}: {
  kind?: CalloutKind;
  title?: ReactNode;
  children?: ReactNode;
  /** Tighter padding for inline messages under a form or a control. */
  compact?: boolean;
  className?: string;
}) {
  const { color, icon } = KINDS[kind];
  return (
    <div
      role={kind === 'danger' ? 'alert' : 'status'}
      data-kind={kind}
      style={{ '--callout': color } as CSSProperties}
      className={cn(
        'text-on-surface flex items-start gap-2.5 rounded-xl border border-[color-mix(in_oklch,var(--callout)_26%,transparent)] bg-[color-mix(in_oklch,var(--callout)_6%,var(--background))]',
        compact ? 'px-3 py-2' : 'px-4 py-3',
        className
      )}
    >
      <span
        aria-hidden="true"
        className="material-symbols-outlined shrink-0 text-(--callout)"
      >
        {icon}
      </span>
      <div className="flex min-w-0 flex-col gap-0.5">
        {title && <p className="text-label-md">{title}</p>}
        {children && <div className="text-body-md">{children}</div>}
      </div>
    </div>
  );
}
