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
