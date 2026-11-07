// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
let writes: string[] = [];
const server = setupServer(
  http.get('/api/supervisors/attachments', ({ request }) => {
    asked = new URL(request.url).search;
    return envelope(rows);
  }),
  http.get('/api/supervisors', () =>
    envelope([
      { id: 'guard', type: 't', system: false },
      { id: 'guard2', type: 't', system: false },
      { id: 'rules', type: 't', system: true },
    ])
  ),
  http.get('/api/sessions/summaries', () =>
    envelope([{ sessionId: 's1', name: 'Alpha' }])
  ),
  http.post('/api/supervisors/attachments', async ({ request }) => {
    writes.push(await request.text());
    return envelope({}, 201);
  })
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  writes = [];
});
afterAll(() => server.close());

const renderCard = (workflowId?: string) =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <EffectiveSupervision sessionId="s1" workflowId={workflowId} />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('EffectiveSupervision', () => {
  it('shows what applies to a workflow, each with where it comes from', async () => {
    renderCard('w1');

    expect(await screen.findByText('declared')).toBeInTheDocument();
    expect(asked).toBe('?session=s1&workflow=w1');
    const items = screen.getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Definition');
    expect(items[1]).toHaveTextContent('Inherited');
    expect(items[2]).toHaveTextContent('Manual');
    expect(items[2]).toHaveTextContent('disabled');
    expect(items[3]).toHaveTextContent('System');
    expect(screen.getByRole('link', { name: 'Configure' })).toHaveAttribute(
      'href',
      '/sessions/s1/workflow/w1/edit'
    );
  });

  it('treats a session-level declaration as its own on the session page', async () => {
    renderCard();

    const items = await screen.findAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Inherited');
    expect(items[1]).toHaveTextContent('Definition');
    expect(screen.getByRole('link', { name: 'Configure' })).toHaveAttribute(
      'href',
      '/sessions/s1/edit'
    );
  });

  it('attaches by hand to where it is, asking which supervisor', async () => {
    const user = userEvent.setup();
    renderCard('w1');
    await user.click(
      await screen.findByRole('button', { name: 'Attach manually' })
    );
    const dialog = await screen.findByRole('dialog');

    await within(dialog).findByRole('option', { name: 'guard' });
    await user.selectOptions(
      within(dialog).getByLabelText('Choose supervisor'),
      'guard2'
    );
    expect(within(dialog).queryByRole('option', { name: 'rules' })).toBeNull();
    expect(within(dialog).getByLabelText('Scope preview')).toHaveTextContent(
      'workflow:s1/w1'
    );
    await user.click(within(dialog).getByRole('button', { name: 'Attach' }));
    await waitFor(() => expect(writes[0]).toContain('"supervisorId":"guard2"'));
    expect(writes[0]).toContain('"scope":"workflow:s1/w1"');
  });

  it('attaches to the session on the session page, and cannot without a supervisor', async () => {
    server.use(http.get('/api/supervisors', () => envelope([])));
    const user = userEvent.setup();
    renderCard();
    await user.click(
      await screen.findByRole('button', { name: 'Attach manually' })
    );
    const dialog = await screen.findByRole('dialog');

    expect(
      await within(dialog).findByText('Create a supervisor first')
    ).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Scope preview')).toHaveTextContent(
      'session:s1'
    );
    expect(
      within(dialog).getByRole('button', { name: 'Attach' })
    ).toBeDisabled();
  });

  it('says so when nothing applies and stays quiet when it cannot be read', async () => {
    server.use(http.get('/api/supervisors/attachments', () => envelope([])));
    const first = renderCard();
    expect(
      await screen.findByText('None apply to this session.')
    ).toBeInTheDocument();
    first.unmount();
    const second = renderCard('w1');
    expect(
      await screen.findByText('None apply to this workflow.')
    ).toBeInTheDocument();
    second.unmount();
    server.use(
      http.get('/api/supervisors/attachments', () => envelope({}, 500))
    );
    renderCard('w1');
    await waitFor(() => expect(screen.queryByText(/None apply/)).toBeNull());
    expect(screen.queryByRole('region', { name: 'Supervisors' })).toBeNull();
  });
});
