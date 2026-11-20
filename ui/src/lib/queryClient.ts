// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './apiClient';

/**
 * Lists that show run statuses (sessions, a workflow's runs, the runs page) re-read on this
 * interval while the tab is visible, so a run that starts or ends shows without a manual refresh.
 * Spread it into the query options: it also makes the data stale on arrival, so opening the page
 * never shows a cached status.
 */
export const LIVE_LIST = { staleTime: 0, refetchInterval: 5000 } as const;

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
