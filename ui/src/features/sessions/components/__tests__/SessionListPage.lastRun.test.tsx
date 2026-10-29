// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { createMockSession } from '@/test/factories/sessionFactory';
import { SessionListPage } from '../SessionListPage';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const run = (sessionId: string, executionId: string, status: string) => ({
  sessionId,
  executionId,
  workflowId: 'check',
  status,
  startTime: '2026-10-29T22:41:03',
  endTime: null,
});

const server = setupServer(
  http.get('/api/sessions/summaries', () =>
    ok([
      createMockSession({
        sessionId: 'dogfood',
        name: 'dogfood',
        projectPath: '/srv/dogfood',
      }),
      createMockSession({ sessionId: 'idle', name: 'idle' }),
    ])
  ),
  // newest first: dogfood's last run is the running one, not the older failure
  http.get('/api/executions', () =>
    ok([run('dogfood', 'a1', 'RUNNING'), run('dogfood', 'b2', 'FAILURE')])
  )
);

beforeAll(() => server.listen());
afterAll(() => server.close());

function Where() {
  return <output data-testid="where">{useLocation().pathname}</output>;
}

function renderList() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <Where />
        <SessionListPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

const rowOf = (name: string) =>
  screen
    .getByText(name, { selector: '.text-label-md' })
    .closest('tr') as HTMLElement;

describe('SessionListPage last run', () => {
  it("shows each session's newest run, and a dash where none was found", async () => {
    renderList();
    const pill = await screen.findByTestId('status-chip');
    expect(pill).toHaveTextContent('Running');
    expect(rowOf('dogfood')).toHaveTextContent('check ·');
    expect(screen.queryByText('Failed')).not.toBeInTheDocument();
    expect(within(rowOf('idle')).getByText('—')).toBeInTheDocument();
  });

  it('labels each cell for the stacked phone layout', async () => {
    renderList();
    await screen.findByTestId('status-chip');
    const cells = within(rowOf('dogfood')).getAllByRole('cell');
    expect(cells.map((cell) => cell.getAttribute('data-label'))).toEqual([
      'Name',
      'Last run',
      'Workflows',
      'Initiator',
      'Tags',
      'Project path',
      null,
    ]);
  });

  it('copies the project path without opening the session', async () => {
    const user = userEvent.setup();
    renderList();
    await user.click(
      await screen.findByRole('button', {
        name: 'Copy project path /srv/dogfood',
      })
    );
    expect(await navigator.clipboard.readText()).toBe('/srv/dogfood');
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/$/);
  });
});
