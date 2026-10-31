// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  api,
  fetchAllPages,
  fetchApi,
  fetchApiPage,
  ApiError,
  type ApiResponse,
} from '../apiClient';
import { API_KEY_REQUIRED_EVENT } from '../apiKey';
import { setMe, type Me } from '../identity';

const ME: Me = {
  authenticated: true,
  keyName: 'v',
  role: 'ADMIN',
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
};

describe('ApiError', () => {
  it('uses the message, falling back to the status', () => {
    expect(new ApiError(404, 'Not found').message).toBe('Not found');
    expect(new ApiError(500).message).toBe('API error: 500');
    expect(new ApiError(400)).toBeInstanceOf(Error);
  });
});

describe('api', () => {
  it('encodes interpolated values only', () => {
    expect(api`/api/sessions/${'a/b?c#d'}/x/${42}`).toBe(
      '/api/sessions/a%2Fb%3Fc%23d/x/42'
    );
    expect(api`/api/sessions`).toBe('/api/sessions');
  });
});

function mockFetch(init: {
  ok: boolean;
  status: number;
  statusText?: string;
  body?: unknown;
  headers?: Record<string, string>;
}) {
  const fetchMock = vi.fn().mockResolvedValueOnce({
    ok: init.ok,
    status: init.status,
    statusText: init.statusText ?? '',
    headers: new Headers(init.headers),
    json:
      init.body === undefined
        ? vi.fn().mockRejectedValueOnce(new SyntaxError('Unexpected token <'))
        : vi.fn().mockResolvedValueOnce(init.body),
  });
  global.fetch = fetchMock;
  return fetchMock;
}

describe('fetchApi', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it.each([200, 201, 202])('returns data on %i', async (status) => {
    const body: ApiResponse<{ id: number }> = {
      timestamp: 't',
      status,
      message: 'ok',
      data: { id: 1 },
      path: '/api/test',
    };
    mockFetch({ ok: true, status, body });
    await expect(fetchApi('/api/test')).resolves.toEqual({ id: 1 });
  });

  it('accepts 204 with no body', async () => {
    mockFetch({ ok: true, status: 204 });
    await expect(fetchApi('/api/test')).resolves.toBeUndefined();
  });

  it('surfaces the server message and typed field errors', async () => {
    const errors = [{ field: 'name', message: 'must not be blank' }];
    mockFetch({
      ok: false,
      status: 400,
      body: {
        status: 400,
        error: 'Bad Request',
        message: 'Validation failed: name',
        errors,
      },
    });
    const err = (await fetchApi('/api/test').catch(
      (e: unknown) => e
    )) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(400);
    expect(err.message).toBe('Validation failed: name');
    expect(err.fieldErrors).toEqual(errors);
  });

  it('falls back to the status text for non-JSON error bodies', async () => {
    mockFetch({ ok: false, status: 502, statusText: 'Bad Gateway' });
    const err = (await fetchApi('/api/test').catch(
      (e: unknown) => e
    )) as ApiError;
    expect(err.status).toBe(502);
    expect(err.message).toBe('Bad Gateway');
  });

  it('throws on a non-JSON success body', async () => {
    mockFetch({ ok: true, status: 200 });
    await expect(fetchApi('/api/test')).rejects.toThrow('Failed to parse JSON');
  });

  it('keeps Content-Type when custom headers are passed', async () => {
    const fetchMock = mockFetch({ ok: true, status: 200, body: { data: 1 } });
    await fetchApi('/api/test', {
      method: 'POST',
      headers: { 'X-Custom': 'v' },
    });
    expect(fetchMock).toHaveBeenCalledWith('/api/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Custom': 'v' },
    });
  });

  it('propagates network errors', async () => {
    global.fetch = vi.fn().mockRejectedValueOnce(new Error('Network failed'));
    await expect(fetchApi('/api/test')).rejects.toThrow('Network failed');
  });

  it('asks for a key on 401 only; a 403 is an error that never opens the prompt', async () => {
    const required = vi.fn();
    window.addEventListener(API_KEY_REQUIRED_EVENT, required);

    mockFetch({ ok: false, status: 401, body: { message: 'bad key' } });
    await expect(fetchApi('/api/x')).rejects.toMatchObject({ status: 401 });
    expect(required).toHaveBeenCalledTimes(1);

    setMe({ ...ME, role: 'VIEWER' });
    mockFetch({
      ok: false,
      status: 403,
      body: { message: 'This API key may not do that' },
    });
    const refused = await fetchApi('/api/x').catch((e: ApiError) => e);
    expect(refused).toMatchObject({ status: 403, kind: 'forbidden' });
    expect((refused as ApiError).message).toBe(
      "Your key (role VIEWER) can't do this"
    );
    expect(required).toHaveBeenCalledTimes(1);

    setMe(null);
    mockFetch({ ok: false, status: 403, body: {} });
    await expect(fetchApi('/api/x')).rejects.toThrow("Your key can't do this");
    window.removeEventListener(API_KEY_REQUIRED_EVENT, required);
  });
});

describe('paged lists', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });

  const page = (data: number[]): ApiResponse<number[]> => ({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/api/list',
  });

  it('returns the cursor the server announced, or null', async () => {
    mockFetch({
      ok: true,
      status: 200,
      body: page([1]),
      headers: { 'X-Next-Cursor': 'c1' },
    });
    await expect(fetchApiPage('/api/list')).resolves.toEqual({
      data: [1],
      nextCursor: 'c1',
    });
    mockFetch({ ok: true, status: 200, body: page([2]) });
    await expect(fetchApiPage('/api/list')).resolves.toEqual({
      data: [2],
      nextCursor: null,
    });
  });

  it('follows the cursor until the last page', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(answer(page([1, 2]), 'a b'))
      .mockResolvedValueOnce(answer(page([3])));
    global.fetch = fetchMock;

    await expect(fetchAllPages('/api/list', 2, 10)).resolves.toEqual([1, 2, 3]);
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/list?limit=2',
      '/api/list?limit=2&cursor=a%20b',
    ]);
  });

  it('adds to a query string that is already there, and stops at maxPages', async () => {
    const fetchMock = vi.fn().mockResolvedValue(answer(page([1]), 'more'));
    global.fetch = fetchMock;

    await expect(fetchAllPages('/api/list?x=1', 1, 2)).resolves.toEqual([1, 1]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/list?x=1&limit=1');
  });
});

function answer(body: unknown, cursor?: string) {
  return {
    ok: true,
    status: 200,
    statusText: '',
    headers: new Headers(cursor ? { 'X-Next-Cursor': cursor } : {}),
    json: () => Promise.resolve(body),
  };
}
