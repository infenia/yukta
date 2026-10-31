// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import type { MetricSample } from '../api';
import { useDashboardParams, useMetricStream } from '../hooks';

let handler: (s: MetricSample) => void = () => undefined;
let streamedUrl: string | null = null;
vi.mock('@/hooks/useEventSourceJson', () => ({
  useEventSourceJson: (url: string | null, onMessage: typeof handler) => {
    streamedUrl = url;
    handler = onMessage;
    return { status: 'open' };
  },
}));

describe('useDashboardParams', () => {
  const wrap =
    (path: string) =>
    ({ children }: { children: ReactNode }) => (
      <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>
    );

  it('defaults to global, 1h, live', () => {
    const { result } = renderHook(() => useDashboardParams(), {
      wrapper: wrap('/'),
    });
    expect(result.current).toMatchObject({
      scope: 'global',
      range: '1h',
      live: true,
    });
  });

  it('reads and writes the URL; an unknown range falls back', () => {
    const { result } = renderHook(() => useDashboardParams(), {
      wrapper: wrap('/?scope=session:s1&range=bogus&live=0'),
    });
    expect(result.current).toMatchObject({
      scope: 'session:s1',
      range: '1h',
      live: false,
    });
    act(() => result.current.setRange('6h'));
    act(() => result.current.setLive(true));
    expect(result.current).toMatchObject({ range: '6h', live: true });
  });
});

describe('useMetricStream', () => {
  it('appends samples to the cached series, trims old points and updates the snapshot', () => {
    const client = new QueryClient();
    const now = Date.now();
    client.setQueryData(
      ['metrics', 'series', 'runs', 'global', '15m'],
      [
        { t: now - 20 * 60_000, v: 1 },
        { t: now - 60_000, v: 2 },
      ]
    );
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    renderHook(() => useMetricStream('global', true), { wrapper });
    expect(streamedUrl).toBe('/api/metrics/stream?scope=global');

    act(() => handler({ metric: 'runs', t: now, v: 3 }));

    expect(
      client.getQueryData(['metrics', 'series', 'runs', 'global', '15m'])
    ).toEqual([
      { t: now - 60_000, v: 2 },
      { t: now, v: 3 },
    ]);
    expect(client.getQueryData(['metrics', 'snapshot', 'global'])).toEqual({
      runs: 3,
    });
  });

  it('stays idle when paused', () => {
    renderHook(() => useMetricStream('global', false), {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={new QueryClient()}>
          {children}
        </QueryClientProvider>
      ),
    });
    expect(streamedUrl).toBeNull();
  });
});
