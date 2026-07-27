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
