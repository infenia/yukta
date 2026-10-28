// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** Time ranges offered as presets; `custom` uses `since` and `until` as typed. */
export const RANGES = ['1h', '24h', '7d', 'custom'] as const;
export type Range = (typeof RANGES)[number] | '';

const RANGE_MS: Record<string, number> = {
  '1h': 3_600_000,
  '24h': 86_400_000,
  '7d': 604_800_000,
};

/** Statuses a run can end in or be in; the server compares the status text, ignoring case. */
export const RUN_STATUSES = [
  'RUNNING',
  'QUEUED',
  'PAUSED',
  'SUCCESS',
  'FAILURE',
  'ERROR',
  'TIMED_OUT',
  'STOPPED',
  'ABORTED',
] as const;

/** What narrows the run list; every field lives in the URL so a view can be shared. */
export interface RunFilters {
  session: string;
  workflow: string;
  status: string;
  text: string;
  range: Range;
  since: string;
  until: string;
}

const FIELDS = ['session', 'workflow', 'status', 'text', 'since', 'until'] as const;

export function parseFilters(params: URLSearchParams): RunFilters {
  const range = params.get('range') ?? '';
  return {
    session: params.get('session') ?? '',
    workflow: params.get('workflow') ?? '',
    status: params.get('status') ?? '',
    text: params.get('text') ?? '',
    range: (RANGES as readonly string[]).includes(range) ? (range as Range) : '',
    since: params.get('since') ?? '',
    until: params.get('until') ?? '',
  };
}

/** The URL for these filters: empty fields are left out. */
export function toParams(filters: RunFilters): URLSearchParams {
  const params = new URLSearchParams();
  for (const key of FIELDS) {
    if (filters[key]) params.set(key, filters[key]);
  }
  if (filters.range) params.set('range', filters.range);
  return params;
}

/** The `since`/`until` bounds to send: a preset counts back from `now`, custom uses the typed ones. */
export function timeBounds(
  filters: RunFilters,
  now: number
): { since?: string; until?: string } {
  if (filters.range === 'custom') {
    return {
      ...(filters.since && { since: filters.since }),
      ...(filters.until && { until: filters.until }),
    };
  }
  const ms = RANGE_MS[filters.range];
  // The server compares zone-less date-times, so send the local clock time of the bound.
  // ponytail: assumes the browser and the server share a time zone; send the server's offset if not
  return ms ? { since: localDateTime(new Date(now - ms)) } : {};
}

const pad = (n: number) => String(n).padStart(2, '0');

function localDateTime(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
