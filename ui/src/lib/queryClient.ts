// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { QueryClient } from '@tanstack/react-query';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60 * 5, // 5 minutes
      gcTime: 1000 * 60 * 10, // 10 minutes
      retry: 1,
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
