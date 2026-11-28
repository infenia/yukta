// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  beforeEach,
  afterAll,
  afterEach,
  vi,
} from 'vitest';
import { renderHook } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { installMockEventSource } from '@/test/mocks/MockEventSource';
import { ControlEventsProvider } from '../ControlEvents';
import { useControlEvents } from '../ControlEventsContext';

const server = setupServer();

beforeAll(() => server.listen());
beforeEach(() => installMockEventSource());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('useControlEvents without a provider', () => {
  it('offers a loadOlder that does nothing and no older events', () => {
    const { result } = renderHook(() => useControlEvents());

    expect(result.current.hasMore).toBe(false);
    expect(() => result.current.loadOlder()).not.toThrow();
  });
});

describe('ControlEventsProvider', () => {
  it('does not ask for an older page while one is already loading', async () => {
    const asked = vi.fn();
    server.use(
      http.get('/api/events/history', ({ request }) => {
        asked(new URL(request.url).search);
        return HttpResponse.json([]);
      })
    );
    const { result } = renderHook(() => useControlEvents(), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={createTestQueryClient()}>
          <ControlEventsProvider>{children}</ControlEventsProvider>
        </QueryClientProvider>
      ),
    });

    // the mount already asked for the newest page; this call arrives while it is in flight
    result.current.loadOlder();

    await vi.waitFor(() => expect(asked).toHaveBeenCalledTimes(1));
    expect(asked).toHaveBeenCalledWith('?limit=200');
  });
});
