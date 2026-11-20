// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { setMe, type Me, type Role } from '@/lib/identity';
import { getConfigValues } from '../../api/config';
import { readableKeys } from '../../lib/catalog';
import { useConfigValues } from '../useConfigValues';

vi.mock('../../api/config', () => ({ getConfigValues: vi.fn() }));

const me = (role: Role): Me => ({
  authenticated: true,
  keyName: 'k',
  role,
  sessions: null,
  authEnabled: true,
  permissions: {
    startRuns: true,
    controlRuns: true,
    editSessions: true,
    deleteSessions: true,
    changeLimits: true,
    sendCommands: true,
  },
});

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={createTestQueryClient()}>
    {children}
  </QueryClientProvider>
);

describe('useConfigValues', () => {
  beforeEach(() => {
    vi.mocked(getConfigValues).mockReset();
    vi.mocked(getConfigValues).mockResolvedValue([
      { key: 'server.port', value: '9858', source: 'configuration file' },
    ]);
  });
  afterEach(() => setMe(null));

  it('does not ask a key that is not ADMIN', () => {
    setMe(me('VIEWER'));
    const { result } = renderHook(() => useConfigValues(), { wrapper });

    expect(result.current).toEqual({ status: 'denied' });
    expect(getConfigValues).not.toHaveBeenCalled();
  });

  it('asks for the readable catalog keys as an ADMIN and returns them by key', async () => {
    setMe(me('ADMIN'));
    const { result } = renderHook(() => useConfigValues(), { wrapper });
    expect(result.current).toEqual({ status: 'loading' });

    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(getConfigValues).toHaveBeenCalledWith(readableKeys());
    expect(
      result.current.status === 'ready' &&
        result.current.values.get('server.port')?.value
    ).toBe('9858');
  });

  it('lets the server decide while the identity is unknown', async () => {
    const { result } = renderHook(() => useConfigValues(), { wrapper });

    await waitFor(() => expect(result.current.status).toBe('ready'));
  });

  it('reports a failure', async () => {
    vi.mocked(getConfigValues).mockRejectedValue(new Error('no'));
    const { result } = renderHook(() => useConfigValues(), { wrapper });

    await waitFor(() => expect(result.current.status).toBe('error'));
  });
});

describe('readableKeys', () => {
  it('keeps the keys the server reads and drops variable-only names', () => {
    const keys = readableKeys();

    expect(keys).toContain('server.port');
    expect(keys).toContain('yukta.api-keys[0].key');
    expect(keys).not.toContain('YUKTA_HOME');
    expect(keys).not.toContain('YUKTA_SECRET_<NAME>');
  });
});
