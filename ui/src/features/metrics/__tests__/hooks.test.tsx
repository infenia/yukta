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
  const wrapperFor =
    (client: QueryClient) =>
    ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );

  it('appends samples to the cached series of the same meter, trims old points and updates the snapshot', () => {
    const client = new QueryClient();
    const now = Date.now();
    const key = 'yukta.executions.rejected{reason=limit}|rate';
    client.setQueryData(
      ['metrics', 'series', 'yukta.executions.rejected', '15m'],
      {
        [key]: [
          { t: now - 20 * 60_000, v: 1 },
          { t: now - 60_000, v: 2 },
        ],
      }
    );
    client.setQueryData(['metrics', 'series', 'yukta.other', '15m'], {});
    renderHook(() => useMetricStream(true), { wrapper: wrapperFor(client) });
    expect(streamedUrl).toBe('/api/metrics/stream');

    act(() =>
      handler({
        time: now,
        values: {
          [key]: 3,
          'yukta.executions.rejected{reason=queue_full}|rate': 0.5,
          'yukta.executions.running': 4,
        },
      })
    );

    expect(
      client.getQueryData([
        'metrics',
        'series',
        'yukta.executions.rejected',
        '15m',
      ])
    ).toEqual({
      [key]: [
        { t: now - 60_000, v: 2 },
        { t: now, v: 3 },
      ],
      'yukta.executions.rejected{reason=queue_full}|rate': [{ t: now, v: 0.5 }],
    });
    expect(
      client.getQueryData(['metrics', 'series', 'yukta.other', '15m'])
    ).toEqual({});
    expect(client.getQueryData(['metrics', 'snapshot'])).toMatchObject({
      'yukta.executions.running': 4,
    });
  });

  it('stays idle when paused', () => {
    renderHook(() => useMetricStream(false), {
      wrapper: wrapperFor(new QueryClient()),
    });
    expect(streamedUrl).toBeNull();
  });
});
