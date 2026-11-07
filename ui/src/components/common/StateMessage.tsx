// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

type Tone = 'neutral' | 'loading' | 'error';

/* Each tone gets an icon tile like the docs cards: accent for neutral and loading, error tint for errors. */
const TONES: Record<Tone, { icon: string; tile: string; title: string }> = {
  neutral: {
    icon: 'inbox',
    tile: 'bg-accent text-accent-foreground',
    title: 'text-on-surface',
  },
  loading: {
    icon: 'progress_activity',
    tile: 'bg-accent text-accent-foreground',
    title: 'text-on-surface',
  },
  error: {
    icon: 'error',
    tile: 'bg-error/10 text-error',
    title: 'text-error',
  },
};

/** Centered loading / empty / error block with an icon tile and an optional retry. */
export function StateMessage({
  tone = 'neutral',
  title,
  children,
  onRetry,
  icon,
  compact = false,
  className,
}: {
  tone?: Tone;
  title: string;
  children?: ReactNode;
  onRetry?: () => void;
  /** Material Symbol to show instead of the tone's default. */
  icon?: string;
  /** Tighter padding, for use inside panels and table cells. */
  compact?: boolean;
  className?: string;
}) {
  const style = TONES[tone];
  return (
    <div
      role={
        tone === 'error' ? 'alert' : tone === 'loading' ? 'status' : undefined
      }
      data-tone={tone}
      className={cn(
        'flex flex-col items-center gap-2 rounded-xl border border-border bg-card/50 text-center',
        compact ? 'px-4 py-6' : 'px-6 py-12',
        className
      )}
    >
      <span
        aria-hidden="true"
        data-testid="state-icon"
        className={cn(
          'mb-1 flex size-9 items-center justify-center rounded-lg',
          style.tile
        )}
      >
        <span
          className={cn(
            'material-symbols-outlined',
            tone === 'loading' && 'animate-spin'
          )}
        >
          {icon ?? style.icon}
        </span>
      </span>
      <p className={cn('text-label-md', style.title)}>{title}</p>
      {children && (
        <p className="max-w-prose text-body-sm text-on-surface-variant">
          {children}
        </p>
      )}
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry} className="mt-1">
          Retry
        </Button>
      )}
    </div>
  );
}
