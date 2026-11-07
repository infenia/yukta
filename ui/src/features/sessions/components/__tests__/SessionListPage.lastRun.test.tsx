// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
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

const run = (executionId: string, status: string) => ({
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
        lastRun: run('a1', 'RUNNING'),
      }),
      createMockSession({ sessionId: 'idle', name: 'idle', lastRun: null }),
      createMockSession({
        sessionId: 'waiting',
        name: 'waiting',
        lastRun: { ...run('q1', 'QUEUED'), startTime: null },
      }),
    ])
  )
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
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
  it("shows each session's newest run from the server, and Not run without one", async () => {
    renderList();
    await screen.findAllByTestId('status-chip');
    const pill = within(rowOf('dogfood')).getByTestId('status-chip');
    // the pill is icon-only: the status word lives in the label, not the text
    expect(pill).toHaveAttribute('aria-label', 'Running');
    expect(pill).not.toHaveTextContent('Running');
    expect(rowOf('dogfood')).toHaveTextContent('check ·');
    expect(within(rowOf('idle')).getByLabelText('Not run')).toBeInTheDocument();
    // a queued run has not started yet
    expect(rowOf('waiting')).toHaveTextContent('check · --');
  });

  it('shows the session name once: the ID appears only when it is not the name slug', async () => {
    server.use(
      http.get('/api/sessions/summaries', () =>
        ok([
          createMockSession({
            sessionId: 'build-pipeline',
            name: 'Build Pipeline',
          }),
          createMockSession({ sessionId: 'ci-7', name: 'Build Pipeline' }),
        ])
      )
    );
    renderList();
    const names = await screen.findAllByText('Build Pipeline', {
      selector: '.text-label-md',
    });
    const [same, differs] = names.map(
      (name) => name.closest('tr') as HTMLElement
    );
    expect(within(same).queryByText('build-pipeline')).not.toBeInTheDocument();
    expect(within(differs).getByText('ci-7')).toBeInTheDocument();
  });

  it('gives the Import session button the shared bordered icon-button style', async () => {
    renderList();
    const link = await screen.findByRole('link', { name: 'Import session' });
    // iconButton('primary'): border from the outline variant, tinted hover from the tone
    expect(link).toHaveClass('border-border', 'hover:bg-primary/10');
  });

  it('labels each cell for the stacked phone layout', async () => {
    renderList();
    await screen.findAllByTestId('status-chip');
    const cells = within(rowOf('dogfood')).getAllByRole('cell');
    expect(cells.map((cell) => cell.getAttribute('data-label'))).toEqual([
      'Name',
      'Last run',
      'Workflows',
      'Creator',
      'Tags',
      'Working directory',
    ]);
  });

  it('copies the working directory without opening the session', async () => {
    const user = userEvent.setup();
    renderList();
    await user.click(
      await screen.findByRole('button', {
        name: 'Copy working directory /srv/dogfood',
      })
    );
    expect(await navigator.clipboard.readText()).toBe('/srv/dogfood');
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/$/);
  });
});
