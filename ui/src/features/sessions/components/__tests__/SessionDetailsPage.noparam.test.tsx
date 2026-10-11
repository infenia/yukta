// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { SessionDetailsPage } from '../SessionDetailsPage';
import { createTestQueryClient } from '../../../../test/utils/testUtils';

vi.mock('react-router', async () => {
  const actual = await vi.importActual('react-router');
  return { ...actual, useParams: () => ({}) };
});

describe('SessionDetailsPage without a session id in the route', () => {
  it('does not look anything up and reports no session', async () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <SessionDetailsPage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText(/session not found|failed to load session details/i)).toBeDefined();
    });
  });
});
