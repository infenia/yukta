// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';

/**
 * An "i" button that opens a short note on what a card or tile shows. A popover, not a hover
 * tooltip, so it works with touch and the keyboard.
 */
export function InfoTip({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <Popover>
      <PopoverTrigger
        aria-label={`About ${title}`}
        className="text-on-surface-variant hover:text-on-surface inline-flex size-5 shrink-0 items-center justify-center rounded-full"
      >
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-base"
        >
          info
        </span>
      </PopoverTrigger>
      <PopoverContent className="text-body-sm text-on-surface">
        {children}
      </PopoverContent>
    </Popover>
  );
}

/** A titled card; its id comes from the title so a tile can link to it. `info` explains the card. */
export function Panel({
  title,
  action,
  info,
  children,
}: {
  title: string;
  action?: ReactNode;
  info?: ReactNode;
  children: ReactNode;
}) {
  const id = `ov-${title.replace(/\W+/g, '-').toLowerCase()}`;
  return (
    <section
      id={`${id}-panel`}
      aria-labelledby={id}
      className="border-border bg-card flex min-w-0 scroll-mt-20 flex-col gap-2 rounded-xl border p-4"
    >
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-1">
          <h2 id={id} className="text-label-md text-on-surface truncate">
            {title}
          </h2>
          {info && <InfoTip title={title}>{info}</InfoTip>}
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}
