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
import { createMockSession } from '@/test/factories/sessionFactory';
import { setMe, type Me } from '@/lib/identity';
import { SessionDetailsPage } from '../SessionDetailsPage';
import { SessionRowActionsMenu } from '../SessionRowActionsMenu';
import { WorkflowCard } from '../WorkflowCard';

const server = setupServer(
  http.get('/api/sessions/sess-1', () =>
    HttpResponse.json({
      status: 200,
      data: { sessionId: 'sess-1', name: 'S', description: 'd', initiator: 'i', tags: [], projectPath: '/p', workflowIds: [] },
    })
  ),
  http.get('/api/sessions/sess-1/workflows', () => HttpResponse.json({ status: 200, data: [] }))
);
beforeAll(() => server.listen());
afterAll(() => server.close());

const wrap = (ui: React.ReactElement, path = '/') => (
  <QueryClientProvider client={createTestQueryClient()}>
    <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
  </QueryClientProvider>
);

describe('where deleting starts', () => {
  it('the row menu offers Delete session… and opens the typed confirmation', async () => {
    const user = userEvent.setup();
    render(wrap(<SessionRowActionsMenu session={createMockSession({ sessionId: 'sess-1' })} />));
    await user.click(screen.getByRole('button', { name: 'Session actions' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Delete session…' }));

    expect(screen.getByRole('dialog', { name: 'Delete session sess-1' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('a workflow card offers a delete button beside its link', async () => {
    const user = userEvent.setup();
    render(
      wrap(
        <WorkflowCard
          sessionId="sess-1"
          workflow={{ workflowId: 'wf1', description: 'd', nodeCount: 1, edgeCount: 0, status: null }}
        />
      )
    );
    await user.click(screen.getByRole('button', { name: 'Delete workflow wf1' }));
    expect(screen.getByRole('dialog', { name: 'Delete workflow wf1' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('the session page has a Delete session… button that opens the confirmation', async () => {
    const user = userEvent.setup();
    render(
      wrap(
        <Routes>
          <Route path="/sessions/:sessionId" element={<SessionDetailsPage />} />
        </Routes>,
        '/sessions/sess-1'
      )
    );
    await user.click(await screen.findByRole('button', { name: /Delete session…/ }));
    expect(screen.getByRole('dialog', { name: 'Delete session sess-1' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('a VIEWER sees the delete entries disabled', async () => {
    const viewer: Me = {
      authenticated: true, keyName: 'v', role: 'VIEWER', sessions: null, authEnabled: true,
      permissions: { startRuns: false, controlRuns: false, editSessions: false, deleteSessions: false, changeLimits: false, sendCommands: false },
    };
    setMe(viewer);
    render(
      wrap(
        <WorkflowCard
          sessionId="sess-1"
          workflow={{ workflowId: 'wf1', description: 'd', nodeCount: 1, edgeCount: 0, status: null }}
        />
      )
    );
    const button = screen.getByRole('button', { name: 'Delete workflow wf1' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', 'Needs ADMIN');
    setMe(null);
  });
});
