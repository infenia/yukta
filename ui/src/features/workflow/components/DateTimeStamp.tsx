// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { cn } from '@/lib/utils';
import {
  formatClockTime,
  formatDate,
  TIME_PLACEHOLDER,
} from '../lib/timeFormat';

/** A timestamp on two lines, the date over the time, so a narrow panel never wraps it mid-value. */
export function DateTimeStamp({
  iso,
  className,
}: {
  iso: string | null | undefined;
  className?: string;
}) {
  const date = formatDate(iso);
  return !iso || date === TIME_PLACEHOLDER ? (
    <span className={cn('num', className)}>{TIME_PLACEHOLDER}</span>
  ) : (
    <time
      dateTime={iso}
      className={cn('num inline-flex flex-col leading-snug', className)}
    >
      <span>{date}</span>
      <span>{formatClockTime(iso)}</span>
    </time>
  );
}

/** A small caption over a value: the cell of a timing grid. */
export function Stamp({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <dt className="text-overline text-on-surface-variant">{label}</dt>
      <dd className="text-code-sm text-on-surface">{children}</dd>
    </div>
  );
}
