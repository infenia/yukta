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

/** Requests for a built-in's versions or engine entry, which must never be sent. */
const notFound: string[] = [];

const builtIn = {
  id: 'rules',
  type: 'rules',
  dryRun: false,
  parents: [],
  settings: {},
  attachments: 0,
  system: true,
  description: '',
};

/** A stored supervisor whose engine entry is still loading, to show the spinner. */
const stored = {
  id: 'lead',
  type: 'failure-guard',
  dryRun: false,
  parents: [],
  settings: {},
  attachments: 0,
  system: false,
  description: '',
};

const server = setupServer(
  http.get('/api/supervisors', () => ok([builtIn, stored])),
  http.get(
    '/api/supervisor-engines/failure-guard',
    () => new Promise(() => {})
  ),
  http.get('/api/supervisors/assignments', () => ok([])),
  http.get('/api/supervisors/:id/assignments', () => ok([])),
  http.get('/api/supervisors/decisions', () => ok([])),
  http.get('/api/supervisor-engines', () => ok([])),
  http.get('/api/supervisor-engines/:type', ({ request }) => {
    notFound.push(new URL(request.url).pathname);
    return HttpResponse.json(
      { timestamp: 't', status: 404, message: 'x', path: '/' },
      { status: 404 }
    );
  }),
  http.get('/api/supervisors/:id/versions', ({ request }) => {
    notFound.push(new URL(request.url).pathname);
    return HttpResponse.json(
      { timestamp: 't', status: 404, message: 'x', path: '/' },
      { status: 404 }
    );
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  notFound.length = 0;
});
afterAll(() => server.close());

const renderAt = (url: string) =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <SupervisorsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('a built-in supervisor', () => {
  it('shows no versions, and asks the server for nothing it does not store', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?supervisor=rules');
    await user.click(await screen.findByRole('tab', { name: 'Changes' }));
    expect(
      await screen.findByText('Built in, no stored versions')
    ).toBeInTheDocument();
    expect(notFound).toEqual([]);
  });

  it('shows its allowed operations at once, without a loading state', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?supervisor=rules');
    await user.click(
      await screen.findByRole('tab', { name: 'Allowed operations' })
    );
    expect(
      await screen.findByRole('columnheader', { name: 'Commands' })
    ).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();
    expect(notFound).toEqual([]);
  });

  it('shows a loading state for a stored supervisor until its engine entry arrives', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?supervisor=lead');
    await user.click(
      await screen.findByRole('tab', { name: 'Allowed operations' })
    );
    expect(await screen.findByText('Loading…')).toBeInTheDocument();
  });
});
