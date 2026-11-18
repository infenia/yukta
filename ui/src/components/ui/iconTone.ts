// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { buttonVariants } from '@/components/ui/button';
import { cn } from '@/lib/utils';

/** Icon color with a faint tint of the same color on hover, for icon-only buttons. */
export const ICON_TONE = {
  success: 'text-success hover:bg-success/10',
  attention: 'text-attention hover:bg-attention/10',
  primary: 'text-primary hover:bg-primary/10',
  destructive: 'text-destructive hover:bg-destructive/10',
  neutral: 'text-on-surface-variant hover:bg-muted',
} as const;

/** An outlined square icon button in the given tone. */
export const iconButton = (tone: keyof typeof ICON_TONE) =>
  cn(buttonVariants({ variant: 'outline', size: 'icon' }), ICON_TONE[tone]);

/** A group of icon segments in a page header: one soft pill, raised a little off the backdrop. */
export const TOOLBAR_GROUP =
  'border-border bg-card/80 shadow-card flex items-center gap-0.5 rounded-full border p-1 backdrop-blur-sm';

/** A hairline between the segments of a toolbar group. */
export const TOOLBAR_DIVIDER = 'bg-border mx-0.5 h-5 w-px shrink-0';

/** An icon segment inside a toolbar group, in the given tone. */
export const toolbarSegment = (tone: keyof typeof ICON_TONE) =>
  cn(
    'flex h-8 min-w-8 items-center justify-center gap-0.5 rounded-full px-1.5 transition-standard focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none active:scale-95 disabled:pointer-events-none disabled:opacity-35 aria-pressed:bg-primary/10 data-popup-open:bg-muted',
    ICON_TONE[tone]
  );
