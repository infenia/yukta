// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as apiClient from '@/lib/apiClient';
import {
  dismissNotification,
  listNotifications,
  markNotificationsRead,
} from '../api';
import { useNotifications } from '../NotificationsContext';

vi.mock('@/lib/apiClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/apiClient')>()),
  fetchApi: vi.fn(),
}));

describe('notifications api', () => {
  beforeEach(() => {
    vi.mocked(apiClient.fetchApi).mockReset();
    vi.mocked(apiClient.fetchApi).mockResolvedValue([]);
  });

  it('reads the inbox with a page of 200', async () => {
    await listNotifications();
    expect(apiClient.fetchApi).toHaveBeenCalledWith(
      '/api/notifications?size=200'
    );
  });

  it('marks the given ids read, or everything when there are none', async () => {
    await markNotificationsRead(['a', 'b']);
    await markNotificationsRead();

    expect(apiClient.fetchApi).toHaveBeenNthCalledWith(
      1,
      '/api/notifications/read',
      {
        method: 'POST',
        body: JSON.stringify({ ids: ['a', 'b'] }),
      }
    );
    expect(apiClient.fetchApi).toHaveBeenNthCalledWith(
      2,
      '/api/notifications/read',
      {
        method: 'POST',
        body: JSON.stringify({ all: true }),
      }
    );
  });

  it('dismisses one item with an empty body', async () => {
    await dismissNotification('a b');
    expect(apiClient.fetchApi).toHaveBeenCalledWith(
      '/api/notifications/a%20b/dismiss',
      { method: 'POST', body: '{}' }
    );
  });

  it('is empty and harmless outside a provider', () => {
    const { result } = renderHook(() => useNotifications());

    expect(result.current.items).toEqual([]);
    expect(result.current.connected).toBe(true);
    expect(() => {
      result.current.dismissToast('x');
      result.current.markRead();
      result.current.dismiss('x');
    }).not.toThrow();
  });
});
