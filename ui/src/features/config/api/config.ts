// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';

/** One setting as the running server sees it (`GET /api/config`). */
export interface ConfigValue {
  key: string;
  /** The value in use, `******` for a secret, or null when nothing sets it. */
  value: string | null;
  /** Where it comes from, or `default` when nothing sets it. */
  source: string;
}

/** Keys per request: all of them in one URL would pass the server's 4096-byte request line. */
const CHUNK = 40;

/** The values in use for `keys`, in order. Needs an ADMIN key. */
export async function getConfigValues(keys: string[]): Promise<ConfigValue[]> {
  const requests: Promise<ConfigValue[]>[] = [];
  for (let i = 0; i < keys.length; i += CHUNK) {
    const query = keys
      .slice(i, i + CHUNK)
      .map((key) => `key=${encodeURIComponent(key)}`)
      .join('&');
    requests.push(fetchApi<ConfigValue[]>(`/api/config?${query}`));
  }
  return (await Promise.all(requests)).flat();
}
