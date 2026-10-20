// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { fetchApi } from '@/lib/apiClient';
import { getEdition } from '../edition';

vi.mock('@/lib/apiClient', () => ({ fetchApi: vi.fn() }));

describe('getEdition', () => {
  it('reads the edition of the server', async () => {
    const info = {
      edition: 'COMMUNITY',
      license: 'Apache-2.0',
      features: [],
      source: null,
    };
    vi.mocked(fetchApi).mockResolvedValue(info);

    await expect(getEdition()).resolves.toEqual(info);
    expect(fetchApi).toHaveBeenCalledWith('/api/edition');
  });
});
