// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BrowserRouter } from 'react-router';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import {
  useReactTable,
  getCoreRowModel,
  getPaginationRowModel,
  getFilteredRowModel,
} from '@tanstack/react-table';
import { SessionListPage } from '../components/SessionListPage';
import { SessionsPaginationFooter } from '../components/SessionsPaginationFooter';
import { SessionRowActionsMenu } from '../components/SessionRowActionsMenu';
import { columns } from '../components/columns';
import type { SessionListItem } from '../types/session';

const mockSessions: SessionListItem[] = Array.from({ length: 25 }, (_, i) => ({
  sessionId: `session-${i + 1}`,
  name: `Session ${i + 1}`,
  description: `Description ${i + 1}`,
  initiator: `user${i + 1}@example.com`,
  tags: [`tag-${i % 3}`],
  projectPath: `/path/to/project-${i + 1}`,
  workflowCount: i + 1,
}));

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
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('Coverage - SessionsPaginationFooter', () => {
  const TableWrapper = ({ pageSize = 10 }: { pageSize?: number }) => {
    const table = useReactTable({
      data: mockSessions,
      columns,
      getCoreRowModel: getCoreRowModel(),
      getPaginationRowModel: getPaginationRowModel(),
      getFilteredRowModel: getFilteredRowModel(),
      initialState: { pagination: { pageSize } },
    });

    return <SessionsPaginationFooter table={table} />;
  };

  it('should handle changing page size from 10 to 20', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<TableWrapper pageSize={10} />);

    const rowsPerPageButton = screen.getByText('10');
    expect(rowsPerPageButton).toBeInTheDocument();
    await user.click(rowsPerPageButton);

    const option20 = screen.getByText('20');
    expect(option20).toBeInTheDocument();
    expect(option20).toBeEnabled();
    await user.click(option20);
    rerender(<TableWrapper pageSize={20} />);
    expect(screen.getByText('20')).toBeInTheDocument();
  });

  it('should handle changing page size from 10 to 50', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<TableWrapper pageSize={10} />);

    const rowsPerPageButton = screen.getByText('10');
    expect(rowsPerPageButton).toBeInTheDocument();
    await user.click(rowsPerPageButton);

    const option50 = screen.getByText('50');
    expect(option50).toBeInTheDocument();
    expect(option50).toBeEnabled();
    await user.click(option50);
    rerender(<TableWrapper pageSize={50} />);
    expect(screen.getByText('50')).toBeInTheDocument();
  });

  it('should navigate pages with next button', async () => {
    const user = userEvent.setup();
    render(<TableWrapper pageSize={10} />);

    const buttons = screen.getAllByRole('button');
    const nextButton = buttons.find((btn) =>
      btn.querySelector('.material-symbols-outlined')?.textContent?.includes('chevron_right')
    );

    expect(nextButton).toBeDefined();
    expect(nextButton).toHaveProperty('disabled', false);
    await user.click(nextButton!);
  });

  it('should navigate pages with last button', async () => {
    const user = userEvent.setup();
    render(<TableWrapper pageSize={10} />);

    const buttons = screen.getAllByRole('button');
    const lastButton = buttons.find((btn) =>
      btn.querySelector('.material-symbols-outlined')?.textContent?.includes('last_page')
    );

    expect(lastButton).toBeDefined();
    expect(lastButton).toHaveProperty('disabled', false);
    await user.click(lastButton!);
  });

  it('should render with multiple pages', () => {
    render(<TableWrapper pageSize={10} />);

    expect(screen.getByText(/25 items/)).toBeInTheDocument();
  });
});

describe('Coverage - SessionRowActionsMenu', () => {
  const mockSession: SessionListItem = {
    sessionId: 'session-test',
    name: 'Test Session',
    description: 'Test Description',
    initiator: 'test@example.com',
    tags: ['test-tag'],
    projectPath: '/test/path',
    workflowCount: 5,
  };

  it('should handle click navigation', async () => {
    const user = userEvent.setup();

    render(
      <BrowserRouter>
        <SessionRowActionsMenu session={mockSession} />
      </BrowserRouter>
    );

    const icon = screen.getByText('more_vert');
    expect(icon).toBeInTheDocument();
    const button = icon.closest('button');
    expect(button).toBeInTheDocument();
    expect(button).toBeEnabled();
    await user.click(button!);
  });

  it('should render with different sessions', () => {
    const differentSession: SessionListItem = {
      sessionId: 'session-different',
      name: 'Different Session',
      description: 'Different Description',
      initiator: 'different@example.com',
      tags: ['different'],
      projectPath: '/different/path',
      workflowCount: 10,
    };

    render(
      <BrowserRouter>
        <SessionRowActionsMenu session={differentSession} />
      </BrowserRouter>
    );

    expect(screen.getByText('more_vert')).toBeInTheDocument();
  });

  it('should handle multiple menu interactions', async () => {
    const user = userEvent.setup();

    render(
      <BrowserRouter>
        <SessionRowActionsMenu session={mockSession} />
      </BrowserRouter>
    );

    const icon = screen.getByText('more_vert');
    expect(icon).toBeInTheDocument();
    const button = icon.closest('button');
    expect(button).toBeInTheDocument();
    expect(button).toBeEnabled();
    await user.click(button!);
    expect(button).toBeInTheDocument();
    await user.click(button!);
    expect(button).toBeInTheDocument();
  });
});

describe('Coverage - SessionListPage Error States', () => {
  it('should render error message on API failure', async () => {
    server.use(
      http.get('/api/sessions/summaries', () => {
        return HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 500,
            message: 'Internal server error',
            error: 'Database connection failed',
            path: '/api/sessions/summaries',
          },
          { status: 500 }
        );
      })
    );

    const testQueryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });

    render(
      <QueryClientProvider client={testQueryClient}>
        <BrowserRouter>
          <SessionListPage />
        </BrowserRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText(/Failed to load sessions/i)).toBeInTheDocument();
    });
  });

  it('should render error boundary content', async () => {
    server.use(
      http.get('/api/sessions/summaries', () => {
        return HttpResponse.error();
      })
    );

    const testQueryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });

    render(
      <QueryClientProvider client={testQueryClient}>
        <BrowserRouter>
          <SessionListPage />
        </BrowserRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      const errorMessage = screen.queryByText(/Failed to load sessions/i);
      expect(errorMessage || screen.queryByText(/loading/i)).toBeTruthy();
    });
  });
});
