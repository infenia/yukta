// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

'use client';

import { cloneElement, type ReactElement, type ReactNode } from 'react';
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
  title,
  ...gate
}: {
  label: ReactNode;
  children: ReactElement<Record<string, unknown>>;
  side?: TooltipPrimitive.Positioner.Props['side'];
  className?: string;
  /** Set by RequirePermission ("Needs OPERATOR"): replaces the label. */
  title?: string;
  /** Also from RequirePermission (`disabled`, `aria-disabled`, `onClick`): passed on to the child. */
  [gateProp: string]: unknown;
}) {
  const text = title ?? label;
  const child = cloneElement(
    children,
    title ? { ...gate, 'aria-description': title } : gate
  );
  if (!text) return child;
  // A disabled button gets no pointer events, so the hover goes on a wrapper.
  const trigger = gate.disabled ? (
    <span className="inline-flex">{child}</span>
  ) : (
    child
  );
  return (
    <TooltipPrimitive.Root>
      <TooltipPrimitive.Trigger render={trigger} />
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
            {text}
          </TooltipPrimitive.Popup>
        </TooltipPrimitive.Positioner>
      </TooltipPrimitive.Portal>
    </TooltipPrimitive.Root>
  );
}

export { Tip, TooltipProvider };
