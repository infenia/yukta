// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

'use client';

import {
  cloneElement,
  useId,
  useState,
  type ReactElement,
  type ReactNode,
} from 'react';
import { Tooltip as TooltipPrimitive } from '@base-ui/react/tooltip';
import { cn } from '@/lib/utils';

/**
 * One delay group for the app: the first tooltip waits `delay`, and moving to another one within
 * Base UI's group `timeout` opens it at once (its popup gets `data-instant`, which skips the animation).
 */
function TooltipProvider({
  delay = 300,
  ...props
}: TooltipPrimitive.Provider.Props) {
  return <TooltipPrimitive.Provider delay={delay} {...props} />;
}

// Light: an inverted slate chip. Dark: an inverted (white) chip would glare, so it is a surface
// lighter than the page (surface-container-high) with a hairline ring. The arrow uses the same fill.
const SURFACE =
  'bg-foreground text-background dark:bg-surface-container-high dark:text-foreground ring-foreground/10 shadow-raised ring-1';

// Fade, scale from the anchor and slide a few pixels in from the trigger's side. The global
// reduced-motion rule in index.css drops the transition.
const MOTION =
  'origin-(--transform-origin) transition-[opacity,scale,translate] duration-150 ease-(--ease-out-soft) data-ending-style:opacity-0 data-ending-style:scale-95 data-ending-style:duration-100 data-starting-style:opacity-0 data-starting-style:scale-95 data-instant:duration-0 data-[side=bottom]:data-starting-style:-translate-y-1 data-[side=left]:data-starting-style:translate-x-1 data-[side=right]:data-starting-style:-translate-x-1 data-[side=top]:data-starting-style:translate-y-1';

/** A 12x6 arrow, pointing up; rotated per side, overlapping the popup by 1px to hide the seam. */
function Arrow() {
  return (
    <TooltipPrimitive.Arrow className="data-[side=bottom]:-top-1.25 data-[side=left]:-right-2 data-[side=left]:rotate-90 data-[side=right]:-left-2 data-[side=right]:-rotate-90 data-[side=top]:-bottom-1.25 data-[side=top]:rotate-180">
      <svg width="12" height="6" viewBox="0 0 12 6" aria-hidden="true">
        <path
          d="M0 6 L4.6 1.4 Q6 0 7.4 1.4 L12 6 Z"
          className="fill-foreground dark:fill-surface-container-high"
        />
        <path
          d="M0 6 L4.6 1.4 Q6 0 7.4 1.4 L12 6"
          className="stroke-foreground/10 fill-none"
        />
      </svg>
    </TooltipPrimitive.Arrow>
  );
}

/** A key cap per key, e.g. `['Shift', 'R']`. */
function Shortcut({ keys }: { keys: string[] }) {
  return (
    <span className="ml-2 inline-flex gap-0.5 align-middle">
      {keys.map((key) => (
        <kbd
          key={key}
          className="text-code-sm inline-flex h-4.5 min-w-4.5 items-center justify-center rounded-sm bg-current/10 px-1 leading-none opacity-80 ring-1 ring-current/15"
        >
          {key}
        </kbd>
      ))}
    </span>
  );
}

/**
 * Wraps one element and shows `label` on hover (mouse only) or keyboard focus, in the app font and
 * theme. Replaces the native `title` attribute, which the OS draws in its own font.
 * A falsy label renders the child untouched. While open, the child is `aria-describedby` the
 * tooltip, unless the label just repeats the child's `aria-label`.
 */
function Tip({
  label,
  children,
  side = 'top',
  className,
  title,
  shortcut,
  description,
  ...gate
}: {
  label: ReactNode;
  children: ReactElement<Record<string, unknown>>;
  side?: TooltipPrimitive.Positioner.Props['side'];
  className?: string;
  /** Set by RequirePermission ("Needs OPERATOR"): replaces the label. */
  title?: string;
  /** Keyboard shortcut shown as key caps after the label, e.g. `'R'` or `['Shift', 'R']`. */
  shortcut?: string | string[];
  /** A second, quieter line under the label. */
  description?: ReactNode;
  /** Also from RequirePermission (`disabled`, `aria-disabled`, `onClick`): passed on to the child. */
  [gateProp: string]: unknown;
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const text = title ?? label;
  const own = children.props;
  const describe =
    open && !title && text !== own['aria-label']
      ? [own['aria-describedby'], id].filter(Boolean).join(' ')
      : undefined;
  const child = cloneElement(children, {
    ...gate,
    ...(title && { 'aria-description': title }),
    ...(describe && { 'aria-describedby': describe }),
  });
  if (!text) return child;
  // A disabled button gets no pointer events, so the hover goes on a wrapper.
  const trigger = gate.disabled ? (
    <span className="inline-flex">{child}</span>
  ) : (
    child
  );
  const keys = typeof shortcut === 'string' ? [shortcut] : shortcut;
  return (
    <TooltipPrimitive.Root onOpenChange={setOpen}>
      <TooltipPrimitive.Trigger render={trigger} />
      <TooltipPrimitive.Portal>
        <TooltipPrimitive.Positioner
          side={side}
          sideOffset={8}
          collisionPadding={8}
          className="isolate z-50"
        >
          <TooltipPrimitive.Popup
            id={id}
            data-slot="tooltip-content"
            className={cn(
              'text-label-sm max-w-64 rounded-lg px-2.5 py-1.5 text-pretty break-words',
              SURFACE,
              MOTION,
              className
            )}
          >
            {text}
            {keys?.length ? <Shortcut keys={keys} /> : null}
            {description ? (
              <span className="mt-0.5 block font-normal opacity-70">
                {description}
              </span>
            ) : null}
            <Arrow />
          </TooltipPrimitive.Popup>
        </TooltipPrimitive.Positioner>
      </TooltipPrimitive.Portal>
    </TooltipPrimitive.Root>
  );
}

export { Tip, TooltipProvider };
