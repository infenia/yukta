// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest';
import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { SessionListPage } from '../components/SessionListPage';

// Grouped column defs of mixed depth force TanStack Table to generate
// placeholder headers, covering the `header.isPlaceholder` branch in
// SessionListPage that is unreachable with the flat production columns.
vi.mock('../components/columns', () => ({
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

const mockSessions = [
  {
    sessionId: 'session-1',
    name: 'Production Build',
    description: 'Main production build session',
    initiator: 'user@example.com',
    tags: ['production'],
    projectPath: '/prod/project',
    workflowCount: 5,
  },
];

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
afterAll(() => server.close());

const renderWithProviders = (component: React.ReactElement) => {
  const testQueryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  return render(
    React.createElement(
      QueryClientProvider,
      { client: testQueryClient },
      React.createElement(BrowserRouter, {}, component)
    )
  );
};

describe('SessionListPage - placeholder headers', () => {
  it('should render empty cell for placeholder headers with grouped columns', async () => {
    // Given: column defs of mixed depth (group + flat leaf) via mocked module
    renderWithProviders(<SessionListPage />);

    // When: the page has loaded its data
    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
    });

    // Then: two header rows exist and the placeholder header cell is empty
    const headerCells = screen.getAllByRole('columnheader');
    expect(screen.getByText('Identity')).toBeInTheDocument();
    expect(screen.getByText('Description')).toBeInTheDocument();

    const placeholderCells = headerCells.filter((cell) => cell.textContent === '');
    expect(placeholderCells.length).toBeGreaterThan(0);
  });
});
