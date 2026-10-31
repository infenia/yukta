// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** "in 12 seconds" / "3 seconds ago" for a timestamp, in the largest fitting unit. */
export function relativeTime(iso: string, now = Date.now()): string {
  const seconds = Math.round((Date.parse(iso) - now) / 1000);
  return Math.abs(seconds) < 60
    ? relative.format(seconds, 'second')
    : relative.format(Math.round(seconds / 60), 'minute');
}

export const relative = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
