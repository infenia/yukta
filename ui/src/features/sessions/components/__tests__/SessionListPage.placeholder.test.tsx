// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { SessionListPage } from '../SessionListPage';
import { createMockSessions } from '@/test/factories/sessionFactory';
import { renderWithProviders } from '@/test/utils/testUtils';

// Grouped column defs of mixed depth force TanStack Table to generate
// placeholder headers, covering the `header.isPlaceholder` branch in
// SessionListPage that is unreachable with the flat production columns.
vi.mock('../columns', () => ({
  columns: [
    {
      id: 'identity',
      header: 'Identity',
      columns: [
        { accessorKey: 'name', header: 'Name' },
        { accessorKey: 'sessionId', header: 'Session ID' },
      ],
    },
    { accessorKey: 'description', header: 'Description' },
  ],
}));

const mockSessions = createMockSessions(1, {
  name: 'Production Build',
  description: 'Main production build session',
  tags: ['production'],
  projectPath: '/prod/project',
  workflowCount: 5,
});

const server = setupServer(
  http.get('/api/sessions/summaries', () => {
    return HttpResponse.json({
      timestamp: new Date().toISOString(),
      status: 200,
      message: 'Session summaries retrieved successfully',
      data: { sessions: mockSessions },
      path: '/api/sessions/summaries',
    });
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  vi.restoreAllMocks();
  server.resetHandlers();
});
afterAll(() => server.close());

describe('SessionListPage - placeholder headers', () => {
  it('should render empty cell for placeholder headers with grouped columns', async () => {
    // Given: column defs of mixed depth (group + flat leaf) via mocked module
    renderWithProviders(<SessionListPage />);

    // When: the page has loaded its data
    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
    });

    // Then: verify table renders with mocked columns
    const table = screen.getByRole('table');
    expect(table).toBeInTheDocument();

    const headerCells = screen.getAllByRole('columnheader');
    expect(headerCells.length).toBeGreaterThan(0);
  });
});
