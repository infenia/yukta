// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ComponentProps } from 'react';
import { Tip } from '@/components/ui/tooltip';
import { ICON_TONE, iconButton } from '@/components/ui/iconTone';
import { cn } from '@/lib/utils';

/** An outlined, toned icon-only button. The label is the accessible name and the tooltip. */
export function IconButton({
  icon,
  label,
  tip = label,
  tone = 'neutral',
  className,
  ...props
}: Omit<ComponentProps<'button'>, 'children'> & {
  icon: string;
  label: string;
  /** The tooltip, when it reads differently from the accessible name. */
  tip?: string;
  tone?: keyof typeof ICON_TONE;
}) {
  return (
    <Tip label={tip}>
      <button
        type="button"
        aria-label={label}
        className={cn(iconButton(tone), className)}
        {...props}
      >
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-base"
        >
          {icon}
        </span>
      </button>
    </Tip>
  );
}
