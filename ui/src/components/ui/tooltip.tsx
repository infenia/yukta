// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

'use client';

import type { ReactElement, ReactNode } from 'react';
import { Tooltip as TooltipPrimitive } from '@base-ui/react/tooltip';
import { cn } from '@/lib/utils';

function TooltipProvider({
  delay = 400,
  ...props
}: TooltipPrimitive.Provider.Props) {
  return <TooltipPrimitive.Provider delay={delay} {...props} />;
}

/**
 * Wraps one element and shows `label` on hover or focus, in the app font and theme.
 * Replaces the native `title` attribute, which the OS draws in its own font.
 * A falsy label renders the child untouched.
 */
function Tip({
  label,
  children,
  side = 'top',
  className,
}: {
  label: ReactNode;
  children: ReactElement<Record<string, unknown>>;
  side?: TooltipPrimitive.Positioner.Props['side'];
  className?: string;
}) {
  if (!label) return children;
  return (
    <TooltipPrimitive.Root>
      <TooltipPrimitive.Trigger render={children} />
      <TooltipPrimitive.Portal>
        <TooltipPrimitive.Positioner
          side={side}
          sideOffset={6}
          className="isolate z-50"
        >
          <TooltipPrimitive.Popup
            data-slot="tooltip-content"
            className={cn(
              'text-label-sm bg-foreground text-background max-w-xs rounded-md px-2.5 py-1.5 font-sans shadow-md',
              className
            )}
          >
            {label}
          </TooltipPrimitive.Popup>
        </TooltipPrimitive.Positioner>
      </TooltipPrimitive.Portal>
    </TooltipPrimitive.Root>
  );
}

export { Tip, TooltipProvider };
