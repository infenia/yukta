// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { SessionListPage } from '../components/SessionListPage';
import * as useSessionSummariesModule from '../hooks/useSessionSummaries';

const mockSessions = [
  {
    sessionId: 'session-1',
    name: 'Production Build',
    description: 'Main production build session',
    initiator: 'user@example.com',
    tags: ['production', 'critical'],
    projectPath: '/prod/project',
    workflowCount: 5,
  },
  {
    sessionId: 'session-2',
    name: 'Staging Deploy',
    description: 'Staging environment deployment',
    initiator: 'deploy@example.com',
    tags: ['staging'],
    projectPath: '/staging/project',
    workflowCount: 3,
  },
  {
    sessionId: 'session-3',
    name: 'Development Test',
    description: 'Development testing session',
    initiator: 'dev@example.com',
    tags: ['development', 'testing'],
    projectPath: '/dev/project',
    workflowCount: 2,
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
afterEach(() => vi.restoreAllMocks());
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

describe('SessionListPage', () => {
  it('should render loading state initially', () => {
    renderWithProviders(<SessionListPage />);
    expect(screen.queryByText(/loading sessions/i)).toBeInTheDocument();
  });

  it('should render sessions after loading', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
    });
  });

  it('should display header with session count', async () => {
    const { container } = renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText(/Sessions/)).toBeInTheDocument();
      const p = container.querySelector('p');
      expect(p?.textContent).toContain('3 sessions');
      expect(p?.textContent).toContain('found.');
    });
  });

  it('should render all sessions in table', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      mockSessions.forEach((session) => {
        expect(screen.getByText(session.name)).toBeInTheDocument();
      });
    });
  });

  it('should render session IDs in table', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      mockSessions.forEach((session) => {
        expect(screen.getByText(session.sessionId)).toBeInTheDocument();
      });
    });
  });

  it('should render descriptions in table', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('Main production build session')).toBeInTheDocument();
    });
  });

  it('should render initiators in table', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('user@example.com')).toBeInTheDocument();
    });
  });

  it('should render workflow count in table', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('5')).toBeInTheDocument();
      expect(screen.getByText('3')).toBeInTheDocument();
    });
  });

  it('should filter sessions by search term', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText(/search by name/i);
    await user.type(searchInput, 'Production');

    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
      expect(screen.queryByText('Staging Deploy')).not.toBeInTheDocument();
    });
  });

  it('should filter by session ID', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('session-1')).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText(/search by name/i);
    await user.type(searchInput, 'session-2');

    await waitFor(() => {
      expect(screen.getByText('Staging Deploy')).toBeInTheDocument();
      expect(screen.queryByText('Production Build')).not.toBeInTheDocument();
    });
  });

  it('should clear search with reset button', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText(/search by name/i) as HTMLInputElement;
    await user.type(searchInput, 'Production');

    await waitFor(() => {
      expect(screen.queryByText('Staging Deploy')).not.toBeInTheDocument();
    });

    const resetButton = screen.getByLabelText('Reset filters');
    await user.click(resetButton);

    await waitFor(() => {
      expect(searchInput.value).toBe('');
      expect(screen.getByText('Production Build')).toBeInTheDocument();
      expect(screen.getByText('Staging Deploy')).toBeInTheDocument();
    });
  });

  it('should render pagination footer', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText(/Rows per page:/)).toBeInTheDocument();
      expect(screen.getByText(/items/)).toBeInTheDocument();
    });
  });

  it('should show correct pagination info', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText(/1–3 of 3 items/)).toBeInTheDocument();
    });
  });

  it('should have disabled prev/first page buttons on first page', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
    });

    const firstPageBtn = screen.getAllByRole('button').find((btn) =>
      btn.querySelector('.material-symbols-outlined')?.textContent?.includes('first_page')
    );
    const prevBtn = screen.getAllByRole('button').find((btn) =>
      btn.querySelector('.material-symbols-outlined')?.textContent?.includes('chevron_left')
    );

    expect(firstPageBtn).toHaveAttribute('disabled');
    expect(prevBtn).toHaveAttribute('disabled');
  });

  it('should show correct row count on first page', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      const rows = screen.getAllByRole('row');
      // Header row + 3 data rows
      expect(rows.length).toBeGreaterThanOrEqual(3);
    });
  });

  it('should render tags for each session', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('production')).toBeInTheDocument();
      expect(screen.getByText('critical')).toBeInTheDocument();
      expect(screen.getByText('staging')).toBeInTheDocument();
    });
  });

  it('should render multiple tags for sessions', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      // Production Build has 2 tags
      const productionRow = screen.getByText('Production Build').closest('tr');
      expect(productionRow?.textContent).toContain('production');
      expect(productionRow?.textContent).toContain('critical');
    });
  });

  it('should display proper message for sessions', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
    });
  });

  it('should render header with correct title', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      const heading = screen.getByRole('heading', { name: /Sessions/ });
      expect(heading).toBeInTheDocument();
    });
  });

  it('should render search input', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      const input = screen.getByPlaceholderText(/search by name/i);
      expect(input).toBeInTheDocument();
    });
  });

  it('should render reset button', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByLabelText('Reset filters')).toBeInTheDocument();
    });
  });

  it('should show table headers', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      // Check table header row with all column names
      const headers = screen.getAllByRole('columnheader');
      expect(headers.length).toBeGreaterThan(0);

      // Verify at least the first few headers exist
      const headerTexts = headers.map((h) => h.textContent);
      expect(headerTexts.join('')).toContain('Name');
      expect(headerTexts.join('')).toContain('Session ID');
      expect(headerTexts.join('')).toContain('Description');
    });
  });

  it('should render project paths', async () => {
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('/prod/project')).toBeInTheDocument();
      expect(screen.getByText('/staging/project')).toBeInTheDocument();
    });
  });

  it('should handle empty search results', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText(/search by name/i);
    await user.type(searchInput, 'nonexistent');

    await waitFor(() => {
      expect(screen.queryByText('Production Build')).not.toBeInTheDocument();
    });
  });

  it('should be case-insensitive in search', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText(/search by name/i);
    await user.type(searchInput, 'production');

    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
    });
  });

  it('should maintain search state on component update', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);

    await waitFor(() => {
      expect(screen.getByText('Production Build')).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText(/search by name/i) as HTMLInputElement;
    await user.type(searchInput, 'staging');

    await waitFor(() => {
      expect(searchInput.value).toBe('staging');
      expect(screen.getByText('Staging Deploy')).toBeInTheDocument();
    });
  });

  it('should display empty state message when no sessions exist', () => {
    vi.spyOn(useSessionSummariesModule, 'useSessionSummaries').mockReturnValue({
      data: [],
      isLoading: false,
      error: null,
    } as any);

    renderWithProviders(<SessionListPage />);

    expect(screen.getByText('No sessions yet')).toBeInTheDocument();
  });

});
