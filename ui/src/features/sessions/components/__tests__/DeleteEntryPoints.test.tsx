// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { setMe, type Me } from '@/lib/identity';
import { SessionDetailsPage } from '../SessionDetailsPage';
import { SessionRowDelete } from '../SessionRowDelete';
import { WorkflowTable } from '../WorkflowTable';

const server = setupServer(
  http.get('/api/sessions/sess-1', () =>
    HttpResponse.json({
      status: 200,
      data: {
        sessionId: 'sess-1',
        name: 'S',
        description: 'd',
        initiator: 'i',
        tags: [],
        projectPath: '/p',
        workflowIds: [],
      },
    })
  ),
  http.get('/api/sessions/sess-1/workflows', () =>
    HttpResponse.json({ status: 200, data: [] })
  )
);
beforeAll(() => server.listen());
afterAll(() => server.close());

const wrap = (ui: React.ReactElement, path = '/') => (
  <QueryClientProvider client={createTestQueryClient()}>
    <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
  </QueryClientProvider>
);

describe('where deleting starts', () => {
  it('a session row has a Delete icon button that opens the typed confirmation', async () => {
    const user = userEvent.setup();
    render(wrap(<SessionRowDelete sessionId="sess-1" />));
    expect(
      screen.queryByRole('button', { name: 'Session actions' })
    ).not.toBeInTheDocument();
    await user.click(
      screen.getByRole('button', { name: 'Delete session sess-1' })
    );

    expect(
      screen.getByRole('dialog', { name: 'Delete session sess-1' })
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('a workflow row has no action menu', () => {
    render(
      wrap(
        <WorkflowTable
          sessionId="sess-1"
          workflows={[
            {
              workflowId: 'wf1',
              description: 'd',
              nodeCount: 1,
              edgeCount: 0,
              status: null,
            },
          ]}
          filter=""
          onFilterChange={() => {}}
        />
      )
    );
    expect(
      screen.queryByRole('button', { name: 'Actions for workflow wf1' })
    ).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('more_vert');
  });

  it('the session page has a visible Delete button that opens the confirmation', async () => {
    const user = userEvent.setup();
    render(
      wrap(
        <Routes>
          <Route path="/sessions/:sessionId" element={<SessionDetailsPage />} />
        </Routes>,
        '/sessions/sess-1'
      )
    );
    await user.click(await screen.findByRole('button', { name: 'Delete' }));
    expect(
      screen.getByRole('dialog', { name: 'Delete session sess-1' })
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('a VIEWER sees the delete buttons disabled, with the reason', async () => {
    const viewer: Me = {
      authenticated: true,
      keyName: 'v',
      role: 'VIEWER',
      sessions: null,
      authEnabled: true,
      permissions: {
        startRuns: false,
        controlRuns: false,
        editSessions: false,
        deleteSessions: false,
        changeLimits: false,
        sendCommands: false,
      },
    };
    setMe(viewer);
    render(wrap(<SessionRowDelete sessionId="sess-1" />));
    const button = screen.getByRole('button', {
      name: 'Delete session sess-1',
    });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', 'Needs ADMIN');
    setMe(null);
  });
});
