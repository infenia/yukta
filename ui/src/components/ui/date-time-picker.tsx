// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

'use client';

import { useState } from 'react';
import { cn } from '@/lib/utils';
import { Calendar } from './calendar';
import { Popover, PopoverContent, PopoverTrigger } from './popover';

interface DateTimePickerProps {
  /** `YYYY-MM-DDTHH:mm` (what `<input type="datetime-local">` uses), or '' for none. */
  value: string;
  onValueChange: (value: string) => void;
  ariaLabel: string;
  placeholder?: string;
  className?: string;
}

const pad = (n: number) => String(n).padStart(2, '0');

/** The typed value as a Date, or undefined when empty or not a date. */
function parse(value: string): Date | undefined {
  const m = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d)/.exec(value);
  return m ? new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]) : undefined;
}

const format = (d: Date) =>
  `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;

const shown = (d: Date) =>
  d.toLocaleString(undefined, {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });

const timeInput =
  'text-on-surface w-6 bg-transparent text-center outline-none disabled:opacity-50';
const footerButton =
  'text-label-md hover:bg-muted transition-standard cursor-pointer rounded-lg px-2 py-1 disabled:cursor-not-allowed disabled:opacity-50';

/**
 * A date and time picker in the app's theme: the Input look on the trigger and the shadcn
 * `Calendar` plus a 24 h time in the popup (the browser's own picker cannot be styled).
 * Changes apply as you pick; the day keeps the time already set.
 */
export function DateTimePicker({
  value,
  onValueChange,
  ariaLabel,
  placeholder = 'Pick a date and time',
  className,
}: DateTimePickerProps) {
  const selected = parse(value);
  const [month, setMonth] = useState<Date>(selected ?? new Date());
  const [open, setOpen] = useState(false);

  const pickDay = (day: Date | undefined) => {
    if (!day) return;
    onValueChange(
      format(
        new Date(
          day.getFullYear(),
          day.getMonth(),
          day.getDate(),
          selected?.getHours() ?? 0,
          selected?.getMinutes() ?? 0
        )
      )
    );
  };
  const setTime = (part: 'h' | 'm', raw: string) => {
    if (!selected) return;
    const n = Math.min(part === 'h' ? 23 : 59, Math.max(0, Number(raw) || 0));
    const next = new Date(selected);
    if (part === 'h') next.setHours(n);
    else next.setMinutes(n);
    onValueChange(format(next));
  };

  return (
    <div className="relative inline-flex">
      <Popover
        open={open}
        onOpenChange={(next) => {
          setOpen(next);
          if (next) setMonth(selected ?? new Date());
        }}
      >
        <PopoverTrigger
          aria-label={ariaLabel}
          className={cn(
            'bg-input/50 text-body-md transition-standard hover:bg-input/80 focus-visible:border-ring focus-visible:ring-ring/30 data-popup-open:border-ring data-popup-open:ring-ring/30 flex h-8 min-w-0 cursor-pointer items-center justify-between gap-2 rounded-2xl border border-transparent pr-2 pl-3 outline-none focus-visible:ring-3 data-popup-open:ring-3',
            selected ? 'text-on-surface pr-14' : 'text-muted-foreground',
            className
          )}
        >
          <span className="truncate">
            {selected ? shown(selected) : placeholder}
          </span>
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-on-surface-variant text-base"
          >
            event
          </span>
        </PopoverTrigger>
        <PopoverContent
          align="start"
          sideOffset={6}
          className="z-70 w-auto p-0"
          aria-label={`${ariaLabel} calendar`}
        >
          <Calendar
            mode="single"
            selected={selected}
            onSelect={pickDay}
            month={month}
            onMonthChange={setMonth}
          />
          <div className="border-border flex items-center gap-2 border-t px-3 py-2">
            <span className="text-label-md text-on-surface-variant">Time</span>
            <div className="bg-input/50 text-body-md flex items-center rounded-xl px-2 py-1">
              <input
                aria-label="Hour"
                inputMode="numeric"
                maxLength={2}
                disabled={!selected}
                value={selected ? pad(selected.getHours()) : '--'}
                onChange={(e) => setTime('h', e.target.value)}
                onFocus={(e) => e.target.select()}
                className={timeInput}
              />
              <span aria-hidden="true" className="text-on-surface-variant">
                :
              </span>
              <input
                aria-label="Minute"
                inputMode="numeric"
                maxLength={2}
                disabled={!selected}
                value={selected ? pad(selected.getMinutes()) : '--'}
                onChange={(e) => setTime('m', e.target.value)}
                onFocus={(e) => e.target.select()}
                className={timeInput}
              />
            </div>
            <button
              type="button"
              className={cn(footerButton, 'text-primary ml-auto')}
              onClick={() => {
                const now = new Date();
                onValueChange(format(now));
                setMonth(now);
              }}
            >
              Now
            </button>
          </div>
          <div className="flex items-center justify-between px-3 pb-2">
            <button
              type="button"
              disabled={!selected}
              className={cn(footerButton, 'text-on-surface-variant')}
              onClick={() => onValueChange('')}
            >
              Clear
            </button>
            <button
              type="button"
              className={cn(footerButton, 'text-primary')}
              onClick={() => setOpen(false)}
            >
              Done
            </button>
          </div>
        </PopoverContent>
      </Popover>
      {selected && (
        <button
          type="button"
          aria-label={`Clear ${ariaLabel}`}
          onClick={() => onValueChange('')}
          className="text-on-surface-variant hover:bg-muted hover:text-on-surface absolute top-1/2 right-7 flex size-5 -translate-y-1/2 cursor-pointer items-center justify-center rounded-full"
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base"
          >
            close
          </span>
        </button>
      )}
    </div>
  );
}
