// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { ICON_TONE } from '@/components/ui/iconTone';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Tip } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';

/** The ⋯ menu of a page header: secondary and destructive actions go here, not in the bar. */
export function OverflowMenu({
  label = 'More actions',
  className,
  children,
}: {
  label?: string;
  /** Replaces the outlined square trigger, e.g. with a toolbar segment. */
  className?: string;
  children: ReactNode;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={(state) => (
          <Tip label={label}>
            <Button
              variant={className ? 'ghost' : 'outline'}
              size="icon"
              aria-label={label}
              className={className ?? ICON_TONE.neutral}
              {...state}
            >
              <span
                className={cn(
                  'material-symbols-outlined',
                  className && 'text-lg'
                )}
                aria-hidden="true"
              >
                {className ? 'more_horiz' : 'more_vert'}
              </span>
            </Button>
          </Tip>
        )}
      />
      <DropdownMenuContent align="end" className="w-max">
        {children}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
