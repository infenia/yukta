// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { knownRole } from '@/lib/identity';
import { authHeaders, requireApiKey } from './apiKey';

const forbiddenMessage = () => {
  const role = knownRole();
  return `Your key${role ? ` (role ${role})` : ''} can't do this`;
};

export interface FieldError {
  field: string;
  message: string;
}

export interface ApiResponse<T> {
  timestamp: string;
  status: number;
  message: string;
  data: T;
  error?: string;
  path: string;
  errors?: FieldError[];
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public error?: string,
    public fieldErrors?: FieldError[],
    public kind?: 'forbidden'
  ) {
    super(error || `API error: ${status}`);
    this.name = 'ApiError';
  }
}

/** Tagged template that URL-encodes every interpolated value: api`/api/sessions/${id}`. */
export function api(
  strings: TemplateStringsArray,
  ...values: unknown[]
): string {
  return strings.reduce(
    (path, part, i) =>
      path +
      part +
      (i < values.length ? encodeURIComponent(String(values[i])) : ''),
    ''
  );
}

export async function fetchApi<T>(
  path: string,
  options?: RequestInit
): Promise<T> {
  return (await fetchApiPage<T>(path, options)).data;
}

/** One page of a list, and the cursor of the next page when the server announced one. */
export interface Page<T> {
  data: T;
  nextCursor: string | null;
}

/** Like fetchApi, but also returns the X-Next-Cursor header of a paged list. */
export async function fetchApiPage<T>(
  path: string,
  options?: RequestInit
): Promise<Page<T>> {
  const response = await fetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
      ...options?.headers,
    },
  });

  // Error bodies from proxies (502 pages) are not JSON; fall back to the status text.
  const json = (await response
    .json()
    .catch(() => null)) as ApiResponse<T> | null;

  // Only a missing or wrong key (401) asks for a key; a valid key that may not do this (403) is an error
  if (response.status === 401) {
    requireApiKey();
  }
  if (!response.ok) {
    throw new ApiError(
      response.status,
      response.status === 403
        ? forbiddenMessage()
        : json?.message || response.statusText,
      json?.errors,
      response.status === 403 ? 'forbidden' : undefined
    );
  }
  if (json === null && response.status !== 204) {
    throw new ApiError(response.status, 'Failed to parse JSON response');
  }
  return {
    data: json?.data as T,
    nextCursor: response.headers.get('X-Next-Cursor'),
  };
}

/**
 * Every item of a paged list: follows X-Next-Cursor until the server stops announcing one, or
 * `maxPages` pages have been read (then the rest is left out rather than the browser kept busy).
 * `path` may already carry a query string.
 */
export async function fetchAllPages<T>(
  path: string,
  pageSize: number,
  maxPages: number
): Promise<T[]> {
  const items: T[] = [];
  const separator = path.includes('?') ? '&' : '?';
  let cursor: string | null = null;
  for (let page = 0; page < maxPages; page += 1) {
    const url: string = `${path}${separator}limit=${pageSize}${
      cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''
    }`;
    const result: Page<T[]> = await fetchApiPage<T[]>(url);
    items.push(...result.data);
    cursor = result.nextCursor;
    if (!cursor) break;
  }
  return items;
}
