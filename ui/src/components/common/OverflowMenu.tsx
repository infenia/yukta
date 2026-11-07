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

/** The ⋯ menu of a page header: secondary and destructive actions go here, not in the bar. */
export function OverflowMenu({
  label = 'More actions',
  children,
}: {
  label?: string;
  children: ReactNode;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={(state) => (
          <Tip label={label}>
            <Button
              variant="outline"
              size="icon"
              aria-label={label}
              className={ICON_TONE.neutral}
              {...state}
            >
              <span className="material-symbols-outlined" aria-hidden="true">
                more_vert
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
