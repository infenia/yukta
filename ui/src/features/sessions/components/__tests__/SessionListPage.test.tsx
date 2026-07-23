// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { SessionListPage } from '../SessionListPage';
import * as useSessionSummariesModule from '../../hooks/useSessionSummaries';
import { createMockSessions } from '@/test/factories/sessionFactory';
import { renderWithProviders } from '@/test/utils/testUtils';

const mockSessions = createMockSessions(3, {
  tags: ['production', 'critical'],
  projectPath: '/prod/project',
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

describe('SessionListPage', () => {
  describe('Loading & Initial Render', () => {
    it('should display loading state initially', () => {
      renderWithProviders(<SessionListPage />);
      expect(screen.queryByText(/loading sessions/i)).toBeInTheDocument();
    });

    it('should render sessions after loading completes', async () => {
      renderWithProviders(<SessionListPage />);

      await waitFor(() => {
        expect(screen.getByText(mockSessions[0].name)).toBeInTheDocument();
      });
    });
  });

  describe('Table Rendering', () => {
    it('should render session list with all columns', async () => {
      renderWithProviders(<SessionListPage />);

      await waitFor(() => {
        mockSessions.forEach((session) => {
          const row = screen.getByText(session.name).closest('tr');
          expect(row).toHaveTextContent(session.sessionId);
          expect(row).toHaveTextContent(session.description);
          expect(row).toHaveTextContent(session.initiator);
          expect(row).toHaveTextContent(session.workflowCount.toString());
          expect(row).toHaveTextContent(session.projectPath);
        });
      });
    });

    it('should render tags for each session', async () => {
      renderWithProviders(<SessionListPage />);

      await waitFor(() => {
        mockSessions.forEach((session) => {
          session.tags.forEach((tag) => {
            expect(screen.getAllByText(tag).length).toBeGreaterThan(0);
          });
        });
      });
    });
  });

  describe('Header & Metadata', () => {
    it('should display header with correct title and session count', async () => {
      const { container } = renderWithProviders(<SessionListPage />);

      await waitFor(() => {
        expect(screen.getByRole('heading', { name: /Sessions/ })).toBeInTheDocument();
        const p = container.querySelector('p');
        expect(p?.textContent).toContain('3 sessions');
        expect(p?.textContent).toContain('found.');
      });
    });

    it('should show table headers', async () => {
      renderWithProviders(<SessionListPage />);

      await waitFor(() => {
        const headers = screen.getAllByRole('columnheader');
        expect(headers.length).toBeGreaterThan(0);
        const headerTexts = headers.map((h) => h.textContent);
        expect(headerTexts.join('')).toContain('Name');
        expect(headerTexts.join('')).toContain('Session ID');
        expect(headerTexts.join('')).toContain('Description');
      });
    });
  });

  describe('Search & Filter', () => {

    it('should filter sessions by search term', async () => {
      const user = userEvent.setup();
      renderWithProviders(<SessionListPage />);

      await waitFor(() => {
        expect(screen.getByText(mockSessions[0].name)).toBeInTheDocument();
      });

      const searchInput = screen.getByPlaceholderText(/search by name/i);
      await user.type(searchInput, 'Session 1');

      await waitFor(() => {
        expect(screen.getByText(mockSessions[0].name)).toBeInTheDocument();
        expect(screen.queryByText(mockSessions[1].name)).not.toBeInTheDocument();
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
        expect(screen.getByText(mockSessions[1].name)).toBeInTheDocument();
        expect(screen.queryByText(mockSessions[0].name)).not.toBeInTheDocument();
      });
    });

    it('should clear search with reset button', async () => {
      const user = userEvent.setup();
      renderWithProviders(<SessionListPage />);

      await waitFor(() => {
        expect(screen.getByText(mockSessions[0].name)).toBeInTheDocument();
      });

      const searchInput = screen.getByPlaceholderText(/search by name/i) as HTMLInputElement;
      await user.type(searchInput, 'Session 1');

      const resetButton = screen.getByLabelText('Reset filters');
      await user.click(resetButton);

      await waitFor(() => {
        expect(searchInput.value).toBe('');
        mockSessions.forEach((session) => {
          expect(screen.getByText(session.name)).toBeInTheDocument();
        });
      });
    });

    it('should handle empty search results', async () => {
      const user = userEvent.setup();
      renderWithProviders(<SessionListPage />);

      await waitFor(() => {
        expect(screen.getByText(mockSessions[0].name)).toBeInTheDocument();
      });

      const searchInput = screen.getByPlaceholderText(/search by name/i);
      await user.type(searchInput, 'nonexistent');

      await waitFor(() => {
        expect(screen.queryByText(mockSessions[0].name)).not.toBeInTheDocument();
      });
    });

    it('should be case-insensitive in search', async () => {
      const user = userEvent.setup();
      renderWithProviders(<SessionListPage />);

      await waitFor(() => {
        expect(screen.getByText(mockSessions[0].name)).toBeInTheDocument();
      });

      const searchInput = screen.getByPlaceholderText(/search by name/i);
      await user.type(searchInput, 'session');

      await waitFor(() => {
        mockSessions.forEach((session) => {
          expect(screen.getByText(session.name)).toBeInTheDocument();
        });
      });
    });

    it('should maintain search state on component update', async () => {
      const user = userEvent.setup();
      renderWithProviders(<SessionListPage />);

      await waitFor(() => {
        expect(screen.getByText(mockSessions[0].name)).toBeInTheDocument();
      });

      const searchInput = screen.getByPlaceholderText(/search by name/i) as HTMLInputElement;
      await user.type(searchInput, 'Session 2');

      await waitFor(() => {
        expect(searchInput.value).toBe('Session 2');
        expect(screen.getByText(mockSessions[1].name)).toBeInTheDocument();
      });
    });
  });

  describe('Pagination', () => {
    it('should render pagination controls', async () => {
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

    it('should disable previous buttons on first page', async () => {
      renderWithProviders(<SessionListPage />);

      await waitFor(() => {
        expect(screen.getByText(mockSessions[0].name)).toBeInTheDocument();
      });

      const firstPageBtn = screen.getByRole('button', { name: 'First page' });
      const prevBtn = screen.getByRole('button', { name: 'Previous page' });

      expect(firstPageBtn).toHaveAttribute('disabled');
      expect(prevBtn).toHaveAttribute('disabled');
    });
  });

  describe('Empty State', () => {
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

  describe('Error State', () => {
    it('should display error message when fetch fails', () => {
      vi.spyOn(useSessionSummariesModule, 'useSessionSummaries').mockReturnValue({
        data: undefined,
        isLoading: false,
        error: new Error('Failed to fetch sessions'),
      } as any);

      renderWithProviders(<SessionListPage />);

      expect(screen.getByText(/Failed to load sessions/)).toBeInTheDocument();
    });
  });
});
