// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { createMockSession } from '@/test/factories/sessionFactory';
import { SessionDetailsPage } from '../SessionDetailsPage';
import { SessionListPage } from '../SessionListPage';

const ok = (data: unknown) =>
  HttpResponse.json({ timestamp: 't', status: 200, message: 'ok', data, path: '/' });

const server = setupServer(
  http.get('/api/sessions/summaries', () => ok([createMockSession()])),
  http.get('/api/sessions/s1', () =>
    ok({
      sessionId: 's1',
      name: 'S one',
      description: 'd',
      initiator: 'me',
      tags: [],
      projectPath: '/p',
      workflowIds: [],
    })
  ),
  http.get('/api/sessions/s1/workflows', () => ok([]))
);

beforeAll(() => server.listen());
afterAll(() => server.close());

function Where() {
  return <output data-testid="where">{useLocation().pathname}</output>;
}

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <div id="header-actions" />
        <Where />
        <Routes>
          <Route index element={<SessionListPage />} />
          <Route path="sessions/:sessionId" element={<SessionDetailsPage />} />
          <Route path="*" element={<p>elsewhere</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('session page entry points to the builder', () => {
  it('offers a New session link on the list', async () => {
    renderAt('/');
    const link = await screen.findByRole('link', { name: /new session/i });
    expect(link).toHaveAttribute('href', '/sessions/new');
  });

  it('opens the editor for a new workflow id typed on the session page', async () => {
    const user = userEvent.setup();
    renderAt('/sessions/s1');
    const input = await screen.findByLabelText('New workflow id');

    await user.click(screen.getByRole('button', { name: /new workflow/i }));
    expect(screen.getByTestId('where')).toHaveTextContent('/sessions/s1');

    await user.type(input, ' my flow ');
    await user.click(screen.getByRole('button', { name: /new workflow/i }));
    expect(screen.getByTestId('where')).toHaveTextContent('/sessions/s1/workflow/my%20flow/edit');
  });
});
