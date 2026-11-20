// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

'use client';

import { Select as SelectPrimitive } from '@base-ui/react/select';
import { cn } from '@/lib/utils';

export interface SelectOption {
  value: string;
  label: string;
}

/**
 * A themed dropdown for a short list of choices: the Input look on the trigger, a rounded popup
 * with a check on the chosen item. Use it instead of NativeSelect where the popup should match
 * the app; the OS draws a native select's popup.
 */
export function Select({
  value,
  onValueChange,
  options,
  ariaLabel,
  className,
}: {
  value: string;
  onValueChange: (value: string) => void;
  options: readonly SelectOption[];
  ariaLabel: string;
  className?: string;
}) {
  return (
    <SelectPrimitive.Root
      value={value}
      onValueChange={(next) => onValueChange(next ?? '')}
      items={options as SelectOption[]}
    >
      <SelectPrimitive.Trigger
        aria-label={ariaLabel}
        data-slot="select-trigger"
        className={cn(
          'bg-input/50 text-on-surface text-body-md transition-standard hover:bg-input/80 focus-visible:border-ring focus-visible:ring-ring/30 data-popup-open:border-ring data-popup-open:ring-ring/30 flex h-9 min-w-0 cursor-pointer items-center justify-between gap-2 rounded-2xl border border-transparent pr-2 pl-3 outline-none focus-visible:ring-3 data-popup-open:ring-3',
          className
        )}
      >
        <SelectPrimitive.Value className="truncate" />
        <SelectPrimitive.Icon
          aria-hidden="true"
          className="material-symbols-outlined text-on-surface-variant text-base transition-transform data-popup-open:rotate-180"
        >
          expand_more
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>
      <SelectPrimitive.Portal>
        <SelectPrimitive.Positioner
          sideOffset={6}
          alignItemWithTrigger={false}
          className="isolate z-50 outline-none"
        >
          <SelectPrimitive.Popup
            data-slot="select-content"
            className="bg-popover text-popover-foreground ring-foreground/5 dark:ring-foreground/10 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95 max-h-(--available-height) min-w-(--anchor-width) origin-(--transform-origin) overflow-y-auto rounded-2xl p-1 shadow-lg ring-1 duration-100 outline-none"
          >
            <SelectPrimitive.List>
              {options.map((o) => (
                <SelectPrimitive.Item
                  key={o.value}
                  value={o.value}
                  className="text-body-md transition-standard data-highlighted:bg-muted flex cursor-pointer items-center gap-2 rounded-xl py-1.5 pr-3 pl-2 outline-none select-none data-selected:font-medium"
                >
                  <SelectPrimitive.ItemIndicator className="material-symbols-outlined text-primary text-base">
                    check
                  </SelectPrimitive.ItemIndicator>
                  <SelectPrimitive.ItemText className="data-[selected]:pl-0">
                    {o.label}
                  </SelectPrimitive.ItemText>
                </SelectPrimitive.Item>
              ))}
            </SelectPrimitive.List>
          </SelectPrimitive.Popup>
        </SelectPrimitive.Positioner>
      </SelectPrimitive.Portal>
    </SelectPrimitive.Root>
  );
}
