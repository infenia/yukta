// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { api, fetchApi, ApiError, type ApiResponse } from '../apiClient';

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
}) {
  const fetchMock = vi.fn().mockResolvedValueOnce({
    ok: init.ok,
    status: init.status,
    statusText: init.statusText ?? '',
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
});
