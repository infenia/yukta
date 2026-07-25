// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { SessionDetailsPage } from '../SessionDetailsPage';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createTestQueryClient } from '../../../../test/utils/testUtils';

vi.mock('react-router', async () => {
  const actual = await vi.importActual('react-router');
  return {
    ...actual,
    useParams: () => ({ sessionId: 'sess-123' }),
  };
});

const server = setupServer(
  http.get('/api/sessions/sess-123', () => {
    return HttpResponse.json({
      status: 200,
      data: {
        sessionId: 'sess-123',
        name: 'Test Session',
        description: 'Desc',
        initiator: 'user1',
        tags: ['tag1'],
        projectPath: '/path',
        workflowIds: ['wf1'],
      },
    });
  }),
  http.get('/api/sessions/sess-123/workflows', () => {
    return HttpResponse.json({
      status: 200,
      data: {
        workflows: [
          {
            workflowId: 'wf1',
            description: 'Workflow 1',
            nodeCount: 5,
            edgeCount: 4,
            status: 'SUCCESS',
          },
        ],
      },
    });
  })
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('SessionDetailsPage', () => {
  afterEach(() => {
    vi.clearAllMocks();
    server.resetHandlers();
  });

  it('renders session details and workflows', async () => {
    const queryClient = createTestQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <SessionDetailsPage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Test Session')).toBeDefined();
    });

    await waitFor(() => {
      expect(screen.getByText('Associated Workflows (1)')).toBeDefined();
    });
  });

  it('shows loading state initially', async () => {
    server.use(
      http.get('/api/sessions/sess-123', async () => {
        await new Promise(resolve => setTimeout(resolve, 100));
        return HttpResponse.json({
          status: 200,
          data: {
            sessionId: 'sess-123',
            name: 'Test Session',
            description: 'Desc',
            initiator: 'user1',
            tags: ['tag1'],
            projectPath: '/path',
            workflowIds: ['wf1'],
          },
        });
      })
    );

    const queryClient = createTestQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <SessionDetailsPage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(screen.getByText('Loading session details...')).toBeDefined();

    await waitFor(() => {
      expect(screen.getByText('Test Session')).toBeDefined();
    }, { timeout: 2000 });
  });

  it('shows error state when session fetch fails', async () => {
    server.use(
      http.get('/api/sessions/sess-123', () => {
        return HttpResponse.json({ status: 500, message: 'Internal Server Error' }, { status: 500 });
      })
    );

    const queryClient = createTestQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <SessionDetailsPage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Failed to load session details')).toBeDefined();
    });
  });

  it('shows error state when workflows fetch fails', async () => {
    server.use(
      http.get('/api/sessions/sess-123/workflows', () => {
        return HttpResponse.json({ status: 500, message: 'Internal Server Error' }, { status: 500 });
      })
    );

    const queryClient = createTestQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <SessionDetailsPage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Failed to load session details')).toBeDefined();
    });
  });

  it('shows not found state when session is null', async () => {
    server.use(
      http.get('/api/sessions/sess-123', () => {
        return HttpResponse.json({ status: 200, data: null });
      })
    );

    const queryClient = createTestQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <SessionDetailsPage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Session not found')).toBeDefined();
    });
  });
});
