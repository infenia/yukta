// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { createMockExecution } from '@/test/factories/workflowFactory';
import { createMockSession } from '@/test/factories/sessionFactory';
import { HistoryPage } from '../HistoryPage';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const requested: string[] = [];
let sessionsFail = false;

const server = setupServer(
  http.get('/api/sessions/summaries', () =>
    sessionsFail
      ? HttpResponse.json({ message: 'down' }, { status: 500 })
      : ok([
          createMockSession({ sessionId: 's1', name: 'First' }),
          createMockSession({ sessionId: 's2', name: '' }),
        ])
  ),
  http.get('/api/workflow/:sid/history', ({ params, request }) => {
    requested.push(`${params.sid}${new URL(request.url).search}`);
    if (params.sid === 's2') return ok([]);
    if (params.sid === 'broken')
      return HttpResponse.json({ message: 'x' }, { status: 500 });
    return ok([
      createMockExecution({ executionId: 'run-1', workflowId: 'wf a' }),
      createMockExecution({
        executionId: 'run-2',
        workflowId: 'wf-b',
        status: 'FAILED',
      }),
    ]);
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  requested.length = 0;
  sessionsFail = false;
});
afterAll(() => server.close());

describe('HistoryPage', () => {
  it('lists the first session’s runs with links to the run on its workflow page', async () => {
    renderWithProviders(<HistoryPage />);
    const link = await screen.findByRole('link', { name: 'run-1' });
    expect(link).toHaveAttribute(
      'href',
      '/sessions/s1/workflow/wf%20a?exec=run-1'
    );
    expect(screen.getByRole('link', { name: 'run-2' })).toBeInTheDocument();
    expect(requested).toEqual(['s1?limit=50']);
  });

  it('switches session and shows the empty state; unnamed sessions show their id', async () => {
    const user = userEvent.setup();
    renderWithProviders(<HistoryPage />);
    await screen.findByRole('link', { name: 'run-1' });

    await user.selectOptions(screen.getByLabelText('Session'), 's2');
    expect(await screen.findByText('No runs yet.')).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 's2' })).toBeInTheDocument();
  });

  it('reports a failure to load sessions', async () => {
    sessionsFail = true;
    renderWithProviders(<HistoryPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to load history'
    );

    sessionsFail = false;
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByLabelText('Session')).toBeInTheDocument();
  });

  it('reports a failure to load a session’s history', async () => {
    server.use(
      http.get('/api/sessions/summaries', () =>
        ok([createMockSession({ sessionId: 'broken', name: 'Broken' })])
      )
    );
    renderWithProviders(<HistoryPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to load history'
    );
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to load history'
    );
    server.resetHandlers();
  });
});
