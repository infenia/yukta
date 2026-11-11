// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { SupervisorsPage } from '../SupervisorsPage';
import { createTestQueryClient } from '@/test/utils/testUtils';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const sup = (id: string, parents: string[] = []) => ({
  id,
  type: 'failure-guard',
  dryRun: false,
  parents,
  settings: {},
  attachments: 0,
  system: false,
  description: '',
});

const summary = (
  unownedWorkflows: { sessionId: string; workflowId: string }[]
) => ok({ unowned: unownedWorkflows.length, unownedWorkflows, conflicts: 0 });

const server = setupServer(
  http.get('/api/supervisors', () => ok([sup('lead'), sup('guard', ['lead'])])),
  http.get('/api/supervisors/assignments', () => ok([])),
  http.get('/api/supervisors/:id/assignments', () => ok([])),
  http.get('/api/supervisor-engines', () => ok([])),
  http.get('/api/supervisors/decisions', () => ok([])),
  http.get('/api/supervision/summary', () =>
    summary([{ sessionId: 's1', workflowId: 'w1' }])
  )
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const renderAt = (url: string) =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <SupervisorsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('Direct control in the supervisors browser', () => {
  it('is selectable and lists the workflows nobody owns', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors');
    await user.click(
      await screen.findByRole('button', { name: /Direct control/ })
    );
    expect(
      await screen.findByRole('heading', { name: 'Direct control' })
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 's1 / w1' })).toHaveAttribute(
      'href',
      '/sessions/s1/workflow/w1'
    );
  });

  it('says so when every workflow has a supervisor', async () => {
    server.use(http.get('/api/supervision/summary', () => summary([])));
    renderAt('/supervisors?supervisor=default');
    expect(await screen.findByText('Nothing is unowned')).toBeInTheDocument();
  });

  it('shows an error when the summary cannot be loaded', async () => {
    server.use(
      http.get('/api/supervision/summary', () =>
        HttpResponse.json(
          { timestamp: 't', status: 500, message: 'boom', path: '/' },
          { status: 500 }
        )
      )
    );
    renderAt('/supervisors?supervisor=default');
    expect(
      await screen.findByText('Could not load what nobody owns.')
    ).toBeInTheDocument();
  });

  it('draws the reporting chain of the selected supervisor', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?supervisor=guard');
    await user.click(await screen.findByRole('tab', { name: 'Reports to' }));
    expect(
      await screen.findByRole('img', { name: 'Reporting chain graph' })
    ).toBeInTheDocument();
  });
});
