// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { EffectiveSupervision } from '../EffectiveSupervision';
import { createTestQueryClient } from '@/test/utils/testUtils';

const envelope = (data: unknown, status = 200) =>
  HttpResponse.json(
    { timestamp: 't', status, message: 'ok', data, path: '/' },
    { status }
  );

const base = {
  enabled: true,
  originSessionId: null,
  originWorkflowId: null,
};
const rows = [
  {
    ...base,
    id: 'd1',
    supervisorId: 'declared',
    scope: 'workflow:s1/w1',
    source: 'DEFINITION',
    originSessionId: 's1',
    originWorkflowId: 'w1',
  },
  {
    ...base,
    id: 'd2',
    supervisorId: 'session-wide',
    scope: 'session:s1',
    source: 'DEFINITION',
    originSessionId: 's1',
  },
  {
    ...base,
    id: 'm1',
    supervisorId: 'by-hand',
    scope: 'global',
    source: 'MANUAL',
    enabled: false,
  },
  {
    ...base,
    id: 'rules',
    supervisorId: 'rules',
    scope: 'global',
    source: 'SYSTEM',
  },
];

let asked = '';
const server = setupServer(
  http.get('/api/supervisors/assignments', ({ request }) => {
    asked = new URL(request.url).search;
    return envelope(rows);
  })
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
});
afterAll(() => server.close());

const renderCard = (workflowId?: string, compact = false) =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <EffectiveSupervision
          sessionId="s1"
          workflowId={workflowId}
          compact={compact}
        />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('EffectiveSupervision', () => {
  it('drops the strip padding when compact, for use inside a header', async () => {
    renderCard('w1', true);

    const region = await screen.findByRole('region', { name: 'Supervisors' });
    expect(region).not.toHaveClass('px-4');
  });

  it('shows what applies to a workflow, each with where it comes from, read-only', async () => {
    renderCard('w1');

    expect(await screen.findByText('declared')).toBeInTheDocument();
    expect(asked).toBe('?session=s1&workflow=w1');
    const items = screen.getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Definition');
    expect(items[1]).toHaveTextContent('Inherited');
    expect(items[2]).toHaveTextContent('Manual');
    expect(items[2]).toHaveTextContent('disabled');
    expect(items[3]).toHaveTextContent('System');
    expect(screen.queryByRole('link', { name: 'Configure' })).toBeNull();
    expect(
      screen.queryByRole('button', { name: 'Attach manually' })
    ).toBeNull();
  });

  it('treats a session-level declaration as its own on the session page', async () => {
    renderCard();

    const items = await screen.findAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Inherited');
    expect(items[1]).toHaveTextContent('Definition');
  });

  it('renders nothing when none apply or when it cannot be read', async () => {
    server.use(http.get('/api/supervisors/assignments', () => envelope([])));
    const first = renderCard('w1');
    await waitFor(() => expect(asked).not.toBe(''));
    expect(screen.queryByRole('region', { name: 'Supervisors' })).toBeNull();
    first.unmount();
    server.use(
      http.get('/api/supervisors/assignments', () => envelope({}, 500))
    );
    renderCard('w1');
    await waitFor(() => expect(screen.queryByText(/Supervision/)).toBeNull());
    expect(screen.queryByRole('region', { name: 'Supervisors' })).toBeNull();
  });
});
