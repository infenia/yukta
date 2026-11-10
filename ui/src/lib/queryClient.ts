// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './apiClient';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60 * 5, // 5 minutes
      gcTime: 1000 * 60 * 10, // 10 minutes
      // A 4xx answer will not change on a retry, so only a network or server failure is retried once
      // (a retry costs about a second of spinner on a missing resource)
      retry: (failureCount, error) =>
        failureCount < 1 &&
        !(
          error instanceof ApiError &&
          error.status >= 400 &&
          error.status < 500
        ),
      refetchOnWindowFocus: false,
    },
    mutations: {
      // A repeated start, signal or rollback must not run twice; the user retries by hand.
      // A start creates its Idempotency-Key inside mutationFn, so a retry would send a new key and
      // run twice: generate the key once per click (in the variables) before enabling retries.
      retry: 0,
    },
  },
});

export default queryClient;
