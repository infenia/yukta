// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { SessionDetailsPage } from '../SessionDetailsPage';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const config = {
  sessionId: 's1',
  name: 'One',
  description: 'd',
  initiator: 'ui',
  tags: {},
  projectPath: '/p',
  workflows: {},
};
const started: unknown[] = [];

const server = setupServer(
  http.get('/api/sessions/s1', () =>
    ok({
      sessionId: 's1',
      name: 'One',
      description: 'd',
      initiator: 'ui',
      tags: [],
      projectPath: '/p',
      workflowIds: ['wf1'],
    })
  ),
  http.get('/api/sessions/s1/workflows', () =>
    ok([
      {
        workflowId: 'wf1',
        description: '',
        nodeCount: 2,
        edgeCount: 1,
        status: null,
      },
    ])
  ),
  http.get('/api/sessions/s1/config', () => ok(config)),
  http.post('/api/workflow/start', async ({ request }) => {
    started.push(await request.json());
    return ok({ executionId: 'new-run' });
  })
);
beforeAll(() => server.listen());
afterAll(() => server.close());

function Where() {
  const location = useLocation();
  return (
    <output data-testid="where">{location.pathname + location.search}</output>
  );
}

function renderPage(clipboardFails = false) {
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  const written: string[] = [];
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: {
      writeText: (text: string) =>
        clipboardFails
          ? Promise.reject(new Error('denied'))
          : (written.push(text), Promise.resolve()),
    },
  });
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={['/sessions/s1']}>
        <Where />
        <Routes>
          <Route path="/sessions/:sessionId" element={<SessionDetailsPage />} />
          <Route path="*" element={null} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
  return { user, written };
}

describe('SessionDetailsPage run and copy as', () => {
  it('runs a workflow from its row and opens the new run', async () => {
    const { user } = renderPage();
    await user.click(await screen.findByRole('button', { name: 'Run wf1' }));
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Run' })
    );
    await waitFor(() =>
      expect(screen.getByTestId('where')).toHaveTextContent(
        '/sessions/s1/workflow/wf1?exec=new-run'
      )
    );
    expect(started).toEqual([
      { sessionId: 's1', workflowId: 'wf1', payload: {} },
    ]);
  });

  it('copies the session as a create_session call, and offers to run each workflow', async () => {
    const { user, written } = renderPage();
    await user.click(await screen.findByRole('button', { name: 'Copy as' }));
    const trigger = await screen.findByRole('menuitem', {
      name: /^Copy as MCP/,
    });
    trigger.focus();
    await user.keyboard('{ArrowRight}');
    expect(
      await screen.findByRole('menuitem', { name: 'Run wf1' })
    ).toBeInTheDocument();
    await user.click(
      await screen.findByRole('menuitem', { name: 'Create this session' })
    );
    await waitFor(() => expect(written).toHaveLength(1));
    expect(JSON.parse(written[0])).toEqual({
      name: 'create_session',
      arguments: { config },
    });
    expect(await screen.findByText('Copied')).toBeInTheDocument();
  });

  it('says when the clipboard refuses', async () => {
    const { user } = renderPage(true);
    await user.click(await screen.findByRole('button', { name: 'Copy as' }));
    const trigger = await screen.findByRole('menuitem', {
      name: /^Copy as CLI/,
    });
    trigger.focus();
    await user.keyboard('{ArrowRight}');
    await user.click(await screen.findByRole('menuitem', { name: 'Run wf1' }));
    expect(await screen.findByText('Copy failed')).toBeInTheDocument();
  });
});
