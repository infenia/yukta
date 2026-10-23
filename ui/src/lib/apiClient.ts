// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { authHeaders, requireApiKey } from './apiKey';

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
    public fieldErrors?: FieldError[]
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

  if (response.status === 401 || response.status === 403) {
    requireApiKey();
  }
  if (!response.ok) {
    throw new ApiError(
      response.status,
      json?.message || response.statusText,
      json?.errors
    );
  }
  if (json === null && response.status !== 204) {
    throw new ApiError(response.status, 'Failed to parse JSON response');
  }
  return json?.data as T;
}
