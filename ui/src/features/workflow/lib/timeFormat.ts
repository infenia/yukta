// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** Placeholder shown when a timestamp is not yet available. */
export const TIME_PLACEHOLDER = '--:--:--';

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** Format an ISO timestamp as a local HH:MM:SS clock time, or the placeholder when absent. */
export function formatClockTime(iso: string | null | undefined): string {
  if (!iso) {
    return TIME_PLACEHOLDER;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return TIME_PLACEHOLDER;
  }
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** Format the duration between two ISO timestamps as e.g. "1m 12s". */
export function formatDuration(
  startIso: string | null | undefined,
  endIso: string | null | undefined
): string {
  if (!startIso || !endIso) {
    return '--';
  }
  const start = new Date(startIso).getTime();
  const end = new Date(endIso).getTime();
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) {
    return '--';
  }
  const totalSeconds = Math.floor((end - start) / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}h ${minutes}m ${seconds}s`;
  }
  if (minutes > 0) {
    return `${minutes}m ${seconds}s`;
  }
  return `${seconds}s`;
}

/** Format an ISO timestamp as a bracketed log prefix, e.g. "[14:22:15]". */
export function formatLogTimestamp(iso: string | null | undefined): string {
  return `[${formatClockTime(iso)}]`;
}

const RELATIVE = new Intl.RelativeTimeFormat('en', {
  numeric: 'auto',
  style: 'short',
});
/** Seconds in each unit, largest first, for {@link formatRelative}. */
const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 31_536_000],
  ['month', 2_592_000],
  ['week', 604_800],
  ['day', 86_400],
  ['hour', 3600],
  ['minute', 60],
];

/** How long ago an ISO timestamp was, e.g. "4 min. ago", "yesterday"; "just now" under a minute. */
export function formatRelative(
  iso: string | null | undefined,
  now = Date.now()
): string {
  const time = iso ? new Date(iso).getTime() : Number.NaN;
  if (Number.isNaN(time)) {
    return '--';
  }
  const seconds = Math.round((time - now) / 1000);
  const unit = UNITS.find(([, size]) => Math.abs(seconds) >= size);
  return unit
    ? RELATIVE.format(Math.round(seconds / unit[1]), unit[0])
    : 'just now';
}
