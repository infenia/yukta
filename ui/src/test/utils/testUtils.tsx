// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import React from 'react';
import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import type { RenderOptions } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router';

export const createTestQueryClient = () =>
  new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });

export const renderWithProviders = (
  component: ReactElement,
  {
    queryClient = createTestQueryClient(),
    ...renderOptions
  }: { queryClient?: QueryClient } & Omit<RenderOptions, 'wrapper'> = {}
) =>
  render(
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>{component}</BrowserRouter>
    </QueryClientProvider>,
    renderOptions
  );

/** Narrow an optional value in tests, failing loudly instead of using non-null assertions. */
export function must<T>(value: T | null | undefined, label = 'value'): T {
  if (value == null) {
    throw new Error(`Expected ${label} to be defined`);
  }
  return value;
}

export const createTestWrapper = (queryClient = createTestQueryClient()) =>
  ({ children }: { children: React.ReactNode }) =>
    React.createElement(
      QueryClientProvider,
      { client: queryClient },
      children
    );
