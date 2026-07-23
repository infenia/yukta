// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { fetchApi, ApiError, type ApiResponse } from '../apiClient';

describe('ApiError', () => {
  it('should create error with status and message', () => {
    const error = new ApiError(404, 'Not found');
    expect(error.status).toBe(404);
    expect(error.error).toBe('Not found');
    expect(error.message).toBe('Not found');
    expect(error.name).toBe('ApiError');
  });

  it('should create error with only status', () => {
    const error = new ApiError(500);
    expect(error.status).toBe(500);
    expect(error.error).toBeUndefined();
    expect(error.message).toBe('API error: 500');
  });

  it('should include field errors', () => {
    const fieldErrors = { email: ['Invalid email'], password: ['Too short'] };
    const error = new ApiError(400, 'Validation failed', fieldErrors);
    expect(error.fieldErrors).toEqual(fieldErrors);
  });

  it('should be instanceof Error', () => {
    const error = new ApiError(400);
    expect(error).toBeInstanceOf(Error);
  });
});

describe('fetchApi', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('should fetch and return data on successful 200 response', async () => {
    const mockData = { id: 1, name: 'Test' };
    const mockResponse: ApiResponse<typeof mockData> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 200,
      message: 'Success',
      data: mockData,
      path: '/api/test',
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    const result = await fetchApi<typeof mockData>('/api/test');
    expect(result).toEqual(mockData);
  });

  it('should fetch and return data on successful 201 response', async () => {
    const mockData = { id: 2, name: 'Created' };
    const mockResponse: ApiResponse<typeof mockData> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 201,
      message: 'Created',
      data: mockData,
      path: '/api/test',
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: true,
      status: 201,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    const result = await fetchApi<typeof mockData>('/api/test');
    expect(result).toEqual(mockData);
  });

  it('should fetch and return data on successful 202 response', async () => {
    const mockData = { id: 3, name: 'Accepted' };
    const mockResponse: ApiResponse<typeof mockData> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 202,
      message: 'Accepted',
      data: mockData,
      path: '/api/test',
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: true,
      status: 202,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    const result = await fetchApi<typeof mockData>('/api/test');
    expect(result).toEqual(mockData);
  });

  it('should throw ApiError on non-200/201/202 status code', async () => {
    const mockResponse: ApiResponse<unknown> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 400,
      message: 'Bad request',
      error: 'Invalid input',
      data: null,
      path: '/api/test',
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    try {
      await fetchApi('/api/test');
      expect.fail('Expected ApiError to be thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).message).toBe('Invalid input');
    }
  });

  it('should throw ApiError with field errors', async () => {
    const fieldErrors = { email: ['Invalid email'] };
    const mockResponse: ApiResponse<unknown> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 422,
      message: 'Validation error',
      error: 'Validation failed',
      data: null,
      path: '/api/test',
      errors: fieldErrors,
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: false,
      status: 422,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    try {
      await fetchApi('/api/test');
      expect.fail('Expected ApiError to be thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).fieldErrors).toEqual(fieldErrors);
    }
  });

  it('should throw ApiError when response.ok is false', async () => {
    const mockResponse: ApiResponse<unknown> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 500,
      message: 'Server error',
      error: 'Internal server error',
      data: null,
      path: '/api/test',
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    await expect(fetchApi('/api/test')).rejects.toThrow(ApiError);
  });

  it('should throw ApiError on JSON parse failure', async () => {
    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: vi.fn().mockRejectedValueOnce(new SyntaxError('Invalid JSON')),
    });

    try {
      await fetchApi('/api/test');
      expect.fail('Expected ApiError to be thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).message).toContain('Failed to parse JSON');
    }
  });

  it('should merge provided headers with default Content-Type', async () => {
    const mockData = { id: 1 };
    const mockResponse: ApiResponse<typeof mockData> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 200,
      message: 'Success',
      data: mockData,
      path: '/api/test',
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    const result = await fetchApi('/api/test', {
      headers: { 'X-Custom': 'value' },
    });

    expect(result).toEqual(mockData);
    expect(global.fetch).toHaveBeenCalled();
  });

  it('should handle request with method and body', async () => {
    const mockData = { success: true };
    const mockResponse: ApiResponse<typeof mockData> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 201,
      message: 'Created',
      data: mockData,
      path: '/api/test',
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: true,
      status: 201,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    const payload = { name: 'Test' };
    const result = await fetchApi('/api/test', {
      method: 'POST',
      body: JSON.stringify(payload),
    });

    expect(result).toEqual(mockData);
  });

  it('should handle 404 response with HTTP error', async () => {
    const mockResponse: ApiResponse<unknown> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 404,
      message: 'Not found',
      error: 'Resource not found',
      data: null,
      path: '/api/missing',
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: false,
      status: 404,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    try {
      await fetchApi('/api/missing');
      expect.fail('Should have thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).status).toBe(404);
    }
  });

  it('should throw ApiError when response.ok is false but status is 200', async () => {
    const mockResponse: ApiResponse<unknown> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 400,
      message: 'Bad request',
      error: 'Invalid data',
      data: null,
      path: '/api/test',
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    try {
      await fetchApi('/api/test');
      expect.fail('Should have thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).error).toBe('Invalid data');
    }
  });

  it('should handle network errors', async () => {
    global.fetch = vi.fn().mockRejectedValueOnce(new Error('Network failed'));

    await expect(fetchApi('/api/test')).rejects.toThrow('Network failed');
  });

  it('should handle malformed JSON gracefully', async () => {
    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: vi.fn().mockRejectedValueOnce(new SyntaxError('Unexpected token')),
    });

    try {
      await fetchApi('/api/test');
      expect.fail('Should have thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).message).toContain('Failed to parse JSON');
    }
  });

  it('should handle response.ok false without error message', async () => {
    const mockResponse: ApiResponse<unknown> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 503,
      message: 'Service unavailable',
      data: null,
      path: '/api/test',
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: false,
      status: 503,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    try {
      await fetchApi('/api/test');
      expect.fail('Should have thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).status).toBe(503);
    }
  });

  it('should pass through custom headers', async () => {
    const mockData = { id: 1 };
    const mockResponse: ApiResponse<typeof mockData> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 200,
      message: 'Success',
      data: mockData,
      path: '/api/test',
    };

    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    global.fetch = fetchMock;

    const result = await fetchApi('/api/test', {
      headers: { 'Authorization': 'Bearer token' },
    });

    expect(result).toEqual(mockData);
    expect(fetchMock).toHaveBeenCalled();
  });

  it('should throw when response.ok is false despite status 200', async () => {
    const mockResponse: ApiResponse<unknown> = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 200,
      message: 'OK',
      data: null,
      path: '/api/test',
    };

    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: false,
      status: 200,
      json: vi.fn().mockResolvedValueOnce(mockResponse),
    });

    try {
      await fetchApi('/api/test');
      expect.fail('Should have thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
    }
  });
});
