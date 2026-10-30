// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import type { SessionDraft } from '../../types';
import { SessionFormPage } from '../SessionFormPage';

const stored = (): SessionDraft => ({
  sessionId: 's1',
  name: 'Name',
  description: 'Desc',
  initiator: 'me',
  tags: { env: 'prod' },
  projectPath: '/p',
  workflows: {
    wf: {
      workflowId: 'wf',
      description: 'w',
      nodes: [{ nodeId: 'a', type: 'API_TRIGGER', config: { k: 1 } }],
      edges: [],
    },
  },
});

let current: SessionDraft = stored();
let loadFails = false;
const posted: SessionDraft[] = [];

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const server = setupServer(
  http.post('/api/sessions/validate/project-path', () =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      data: { exists: true, directory: true },
      path: '/',
    })
  ),
  http.get('/api/sessions/:sid/config', () =>
    loadFails
      ? HttpResponse.json({ message: 'x' }, { status: 500 })
      : ok(current)
  ),
  http.post('/api/sessions', async ({ request }) => {
    posted.push((await request.json()) as SessionDraft);
    return ok(null);
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  current = stored();
  loadFails = false;
  posted.length = 0;
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

function renderEdit() {
  window.history.pushState({}, '', '/sessions/s1/edit');
  return renderWithProviders(
    <Routes>
      <Route path="sessions/new" element={<SessionFormPage />} />
      <Route path="sessions/:sessionId/edit" element={<SessionFormPage />} />
      <Route path="*" element={<p>elsewhere</p>} />
    </Routes>
  );
}

describe('SessionFormPage in edit mode', () => {
  it('goes back to the session on Cancel', async () => {
    const user = userEvent.setup();
    renderEdit();
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(window.location.pathname).toBe('/sessions/s1');
    expect(posted).toEqual([]);
  });

  it('shows the session with its id read-only and its tags', async () => {
    renderEdit();
    expect(await screen.findByDisplayValue('Name')).toBeInTheDocument();
    expect(screen.getByLabelText('Session ID')).toHaveAttribute('readonly');
    expect(
      screen.getByRole('button', { name: 'env: prod' })
    ).toBeInTheDocument();
  });

  it('saves edited metadata and tags and leaves the workflows as they are', async () => {
    const user = userEvent.setup();
    renderEdit();
    const name = await screen.findByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'Renamed');
    await user.type(screen.getByLabelText('Add a tag'), 'team: core{Enter}');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0]).toEqual({
      ...stored(),
      name: 'Renamed',
      tags: { env: 'prod', team: 'core' },
    });
    await waitFor(() =>
      expect(screen.getByText('elsewhere')).toBeInTheDocument()
    );
    expect(window.location.pathname).toBe('/sessions/s1');
  });

  it('warns before overwriting a session that changed since it was opened', async () => {
    const user = userEvent.setup();
    renderEdit();
    const name = await screen.findByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'Mine');
    current = {
      ...stored(),
      description: 'changed elsewhere',
      workflows: {
        ...stored().workflows,
        extra: { workflowId: 'extra', description: '', nodes: [], edges: [] },
      },
    };

    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'changed since you opened it'
    );
    expect(posted).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0].name).toBe('Mine');
    expect(Object.keys(posted[0].workflows)).toEqual(['wf', 'extra']);
  });

  it('refuses a tag without a key or with a key in use, and saves the others', async () => {
    const user = userEvent.setup();
    renderEdit();
    await screen.findByLabelText('Name');
    const add = screen.getByLabelText('Add a tag');
    await user.type(add, ': x{Enter}');
    expect(
      screen.getByText('A tag needs a key: write key: value.')
    ).toBeInTheDocument();
    await user.clear(add);
    await user.type(add, 'env: dev{Enter}');
    expect(
      screen.getByText('The tag key "env" is used already.')
    ).toBeInTheDocument();
    await user.clear(add);
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0]).toMatchObject({ tags: { env: 'prod' } });
  });

  it('shows a failed save and lets the user retry', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('/api/sessions', () =>
        HttpResponse.json({ message: 'disk full' }, { status: 500 })
      )
    );
    renderEdit();
    await screen.findByLabelText('Name');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('disk full');
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  });

  it('reports a session that cannot be loaded', async () => {
    loadFails = true;
    renderEdit();
    expect(
      await screen.findByText('Failed to load the session')
    ).toBeInTheDocument();
  });

  it('keeps the create form for a new session, with tags', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/sessions/new');
    renderWithProviders(
      <Routes>
        <Route path="sessions/new" element={<SessionFormPage />} />
        <Route path="*" element={<p>elsewhere</p>} />
      </Routes>
    );
    await user.type(screen.getByLabelText('Session ID'), 'fresh');
    await user.type(screen.getByLabelText('Name'), 'n');
    await user.type(screen.getByLabelText('Description'), 'd');
    await user.type(screen.getByLabelText('Project path'), '/w');
    await user.type(screen.getByLabelText('Add a tag'), 'env: dev{Enter}');
    await user.click(screen.getByRole('button', { name: 'Create session' }));

    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0]).toMatchObject({
      sessionId: 'fresh',
      tags: { env: 'dev' },
      workflows: { main: { workflowId: 'main' } },
    });
  });
});
