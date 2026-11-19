// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fetchApi } from '@/lib/apiClient';
import { getConfigValues } from '../config';

vi.mock('@/lib/apiClient', () => ({ fetchApi: vi.fn() }));

const keysIn = (url: string) => [
  ...new URL(url, 'http://x').searchParams.getAll('key'),
];

describe('getConfigValues', () => {
  beforeEach(() => {
    vi.mocked(fetchApi).mockReset();
    vi.mocked(fetchApi).mockImplementation(async (url: string) =>
      keysIn(url).map((key) => ({ key, value: null, source: 'default' }))
    );
  });

  it('asks nothing for no keys', async () => {
    expect(await getConfigValues([])).toEqual([]);
    expect(fetchApi).not.toHaveBeenCalled();
  });

  it('splits the keys into requests of 40, keeping the order', async () => {
    const keys = Array.from({ length: 95 }, (_, i) => `yukta.k${i}`);
    const values = await getConfigValues(keys);

    expect(
      vi.mocked(fetchApi).mock.calls.map(([url]) => keysIn(url).length)
    ).toEqual([40, 40, 15]);
    expect(values.map((v) => v.key)).toEqual(keys);
  });

  it('encodes keys that have brackets', async () => {
    await getConfigValues(['yukta.api-keys[0].key']);

    const [url] = vi.mocked(fetchApi).mock.calls[0];
    expect(url).toBe('/api/config?key=yukta.api-keys%5B0%5D.key');
  });
});
