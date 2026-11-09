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
import { newSession } from '../SessionFormPage.helpers';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const stored = (): SessionDraft => ({
  sessionId: 's1',
  name: 'Name',
  description: 'Desc',
  initiator: 'me',
  tags: {},
  projectPath: '/p',
  workflows: {
    wf: { workflowId: 'wf', description: 'w', nodes: [], edges: [] },
  },
  assignments: [{ supervisor: 'guard' }],
});

const posted: SessionDraft[] = [];
let refuse = false;
const server = setupServer(
  http.get('/api/templates', () => ok([])),
  http.get('/api/plugins', () => ok([])),
  http.post('/api/sessions/validate/project-path', () =>
    ok({ exists: true, directory: true })
  ),
  http.get('/api/supervisors', () =>
    ok([{ id: 'guard', type: 't', system: false }])
  ),
  http.get('/api/sessions/:sid/config', () => ok(stored())),
  http.post('/api/sessions', async ({ request }) => {
    if (refuse) {
      return HttpResponse.json(
        {
          status: 400,
          message: 'Invalid supervisors',
          errors: [
            {
              field: 'assignments[0].supervisor',
              message: "no supervisor 'x'",
            },
          ],
        },
        { status: 400 }
      );
    }
    posted.push((await request.json()) as SessionDraft);
    return ok(null);
  })
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  posted.length = 0;
  refuse = false;
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

const renderAt = (path: string) => {
  window.history.pushState({}, '', path);
  return renderWithProviders(
    <Routes>
      <Route path="sessions/new" element={<SessionFormPage />} />
      <Route path="sessions/:sessionId/edit" element={<SessionFormPage />} />
      <Route path="*" element={<p>elsewhere</p>} />
    </Routes>
  );
};

describe('the supervisors of a session in the form', () => {
  it('creates a session that declares a supervisor', async () => {
    const user = userEvent.setup();
    renderAt('/sessions/new');
    await user.click(
      await screen.findByRole('button', { name: 'Start blank' })
    );
    await user.type(screen.getByLabelText('Session ID'), 'mine');
    await user.type(screen.getByLabelText('Name'), 'Mine');
    await user.type(screen.getByLabelText('Description'), 'd');
    await user.type(screen.getByLabelText('Working directory'), '/p');
    const add = screen.getByRole('button', { name: 'Add supervisor' });
    await waitFor(() => expect(add).toBeEnabled());
    await user.click(add);
    await user.click(screen.getByRole('button', { name: 'Create session' }));

    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0].assignments).toEqual([{ supervisor: 'guard' }]);
  });

  it('creates a session without any and sends no field for them', async () => {
    const user = userEvent.setup();
    renderAt('/sessions/new');
    await user.click(
      await screen.findByRole('button', { name: 'Start blank' })
    );
    await user.type(screen.getByLabelText('Session ID'), 'plain');
    await user.type(screen.getByLabelText('Name'), 'Plain');
    await user.type(screen.getByLabelText('Description'), 'd');
    await user.type(screen.getByLabelText('Working directory'), '/p');
    await user.click(screen.getByRole('button', { name: 'Create session' }));

    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0]).not.toHaveProperty('supervisors');
  });

  it('edits a session, removing its last supervisor', async () => {
    const user = userEvent.setup();
    renderAt('/sessions/s1/edit');
    await user.click(await screen.findByLabelText('Remove supervisor 1'));
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0].assignments).toEqual([]);
  });

  it('shows what the server says about the supervisors, on the field', async () => {
    refuse = true;
    const user = userEvent.setup();
    renderAt('/sessions/s1/edit');
    await user.click(await screen.findByRole('button', { name: 'Save' }));

    const shown = await screen.findAllByText(
      "assignments[0].supervisor: no supervisor 'x'"
    );
    expect(shown.some((el) => el.getAttribute('role') === 'alert')).toBe(true);
  });

  it('puts supervisors into the session it builds only when there are some', () => {
    const fields = {
      sessionId: 's',
      name: 'n',
      description: 'd',
      initiator: 'i',
      projectPath: '/p',
    };
    expect(
      newSession(fields, {}, undefined, [{ supervisor: 'g' }]).assignments
    ).toEqual([{ supervisor: 'g' }]);
    expect(newSession(fields, {})).not.toHaveProperty('supervisors');
  });
});
