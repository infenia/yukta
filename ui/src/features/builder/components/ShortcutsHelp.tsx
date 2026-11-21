// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { buttonVariants } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';

const SHORTCUTS: [string, string][] = [
  ['Ctrl/⌘ Z', 'Undo'],
  ['Ctrl/⌘ Shift Z, or Ctrl Y', 'Redo'],
  ['Delete', 'Remove the selected node'],
  ['Esc', 'Deselect the node'],
  ['Enter in the search box', 'Pick the first step listed'],
];

/** The keys the editor answers to, in a small popover. */
export function ShortcutsHelp() {
  return (
    <Popover>
      <PopoverTrigger
        // keys are for a keyboard: a phone has no use for the button
        className={cn(
          buttonVariants({ variant: 'ghost' }),
          'hidden sm:inline-flex'
        )}
        aria-label="Keyboard shortcuts"
      >
        Shortcuts
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80">
        <dl className="flex flex-col gap-2">
          {SHORTCUTS.map(([keys, what]) => (
            <div
              key={keys}
              className="flex items-baseline justify-between gap-3"
            >
              <dt className="text-code-sm bg-muted rounded px-1.5 py-0.5">
                {keys}
              </dt>
              <dd className="text-body-md text-right">{what}</dd>
            </div>
          ))}
        </dl>
      </PopoverContent>
    </Popover>
  );
}
