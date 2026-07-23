// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import queryClient from '../queryClient';

describe('queryClient', () => {
  it('should be an instance of QueryClient', () => {
    expect(queryClient).toBeInstanceOf(QueryClient);
  });

  it('should have default options configured', () => {
    const defaultOptions = queryClient.getDefaultOptions();
    expect(defaultOptions).toBeDefined();
    expect(defaultOptions.queries).toBeDefined();
    expect(defaultOptions.mutations).toBeDefined();
  });

  describe('query default options', () => {
    it('should have staleTime set to 5 minutes', () => {
      const staleTime = queryClient.getDefaultOptions().queries?.staleTime;
      expect(staleTime).toBe(1000 * 60 * 5);
    });

    it('should have gcTime set to 10 minutes', () => {
      const gcTime = queryClient.getDefaultOptions().queries?.gcTime;
      expect(gcTime).toBe(1000 * 60 * 10);
    });

    it('should have retry set to 1', () => {
      const retry = queryClient.getDefaultOptions().queries?.retry;
      expect(retry).toBe(1);
    });

    it('should have refetchOnWindowFocus disabled', () => {
      const refetchOnWindowFocus =
        queryClient.getDefaultOptions().queries?.refetchOnWindowFocus;
      expect(refetchOnWindowFocus).toBe(false);
    });

    it('should return all query options together', () => {
      const queryOptions = queryClient.getDefaultOptions().queries;
      expect(queryOptions).toEqual({
        staleTime: 1000 * 60 * 5,
        gcTime: 1000 * 60 * 10,
        retry: 1,
        refetchOnWindowFocus: false,
      });
    });
  });

  describe('mutation default options', () => {
    it('should have retry set to 1', () => {
      const retry = queryClient.getDefaultOptions().mutations?.retry;
      expect(retry).toBe(1);
    });

    it('should return mutation options', () => {
      const mutationOptions = queryClient.getDefaultOptions().mutations;
      expect(mutationOptions).toEqual({
        retry: 1,
      });
    });
  });

  describe('time constants', () => {
    it('should use milliseconds for all time values', () => {
      const staleTime = queryClient.getDefaultOptions().queries?.staleTime;
      const gcTime = queryClient.getDefaultOptions().queries?.gcTime;
      expect(staleTime).toBe(300000);
      expect(gcTime).toBe(600000);
    });

    it('should have gcTime greater than staleTime', () => {
      const staleTime = queryClient.getDefaultOptions().queries?.staleTime as number;
      const gcTime = queryClient.getDefaultOptions().queries?.gcTime as number;
      expect(gcTime > staleTime).toBe(true);
    });
  });

  describe('queryClient configuration', () => {
    it('should be a singleton instance exported as default', () => {
      expect(queryClient).toBeDefined();
      expect(queryClient).toBeInstanceOf(QueryClient);
    });

    it('should be usable for cache operations', () => {
      expect(() => queryClient.clear()).not.toThrow();
    });

    it('should have methods for query management', () => {
      expect(queryClient.getQueryData).toBeDefined();
      expect(queryClient.setQueryData).toBeDefined();
      expect(queryClient.removeQueries).toBeDefined();
      expect(queryClient.invalidateQueries).toBeDefined();
    });
  });

  describe('query behavior', () => {
    it('should not refetch on window focus', () => {
      const refetchOnWindowFocus =
        queryClient.getDefaultOptions().queries?.refetchOnWindowFocus;
      expect(refetchOnWindowFocus).toBe(false);
    });

    it('should retry failed requests once', () => {
      const queryRetry = queryClient.getDefaultOptions().queries?.retry;
      const mutationRetry = queryClient.getDefaultOptions().mutations?.retry;
      expect(queryRetry).toBe(1);
      expect(mutationRetry).toBe(1);
    });

    it('should cache data for 10 minutes before garbage collection', () => {
      const gcTime = queryClient.getDefaultOptions().queries?.gcTime;
      expect(gcTime).toBe(600000);
    });

    it('should mark data as stale after 5 minutes', () => {
      const staleTime = queryClient.getDefaultOptions().queries?.staleTime;
      expect(staleTime).toBe(300000);
    });
  });
});
