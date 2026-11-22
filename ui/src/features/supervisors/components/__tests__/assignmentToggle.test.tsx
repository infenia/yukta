// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { AssignmentCard } from '../SupervisorDetailTabs';
import type { AttachmentView } from '../../types';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const assignment = (extra: Partial<AttachmentView> = {}): AttachmentView => ({
  id: 'a1',
  supervisorId: 'guard',
  scope: 'workflow:s/w',
  enabled: true,
  source: 'MANUAL',
  originSessionId: null,
  originWorkflowId: null,
  ...extra,
});

let stored: AttachmentView[] = [];
let put: unknown[] = [];
let failPut = false;
const server = setupServer(
  http.get('/api/supervision/ownership', () =>
    ok({
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: true,
      run: 'guard',
      nodes: Object.fromEntries(
        [
          'a',
          'b',
          'c',
          'd',
          'e',
          'f',
          'g',
          'h',
          'i',
          'j',
          'k',
          'l',
          'm',
          'n',
        ].map((id, i) => [id, i === 0 ? 'other' : 'guard'])
      ),
    })
  ),
  http.get('/api/supervisors/assignments', () => ok(stored)),
  http.get('/api/supervisors/:id/assignments', () => ok(stored)),
  http.put('/api/supervisors/:id/assignments', async ({ request }) => {
    put.push(await request.json());
    if (failPut) {
      return HttpResponse.json(
        {
          timestamp: 't',
          status: 500,
          message: 'The server said no',
          path: '/',
        },
        { status: 500 }
      );
    }
    return ok(stored.map((a) => ({ ...a, enabled: !a.enabled })));
  })
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  stored = [];
  put = [];
  failPut = false;
});
afterAll(() => server.close());

const show = (a: AttachmentView) => {
  stored = [a];
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <AssignmentCard supervisorId="guard" assignment={a} />
      </MemoryRouter>
    </QueryClientProvider>
  );
};

describe('AssignmentCard', () => {
  it('asks before disabling an assignment made by hand, then saves it off', async () => {
    const user = userEvent.setup();
    show(assignment());
    await user.click(
      await screen.findByRole('button', { name: /^Disable guard on / })
    );
    const dialog = screen.getByRole('alertdialog', {
      name: 'Disable the assignment?',
    });
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(put).toEqual([]);

    await user.click(
      screen.getByRole('button', { name: /^Disable guard on / })
    );
    await user.click(screen.getByRole('button', { name: 'Disable now' }));
    await waitFor(() => expect(put).toHaveLength(1));
    expect(put[0]).toEqual([
      { target: 'workflow:s/w', enabled: false, locked: false },
    ]);
  });

  it('turns a disabled assignment back on at once, and marks it Disabled', async () => {
    const user = userEvent.setup();
    show(assignment({ enabled: false }));
    expect(await screen.findByText('Disabled')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^Enable guard on / }));
    await waitFor(() => expect(put).toHaveLength(1));
    expect(put[0]).toEqual([
      { target: 'workflow:s/w', enabled: true, locked: false },
    ]);
  });

  it('shows that it is saving while the change is on its way', async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.put('/api/supervisors/:id/assignments', async () => {
        await gate;
        return ok([]);
      })
    );
    show(assignment({ enabled: false }));
    await user.click(
      await screen.findByRole('button', { name: /^Enable guard on / })
    );
    // an icon button: while it saves it is disabled and shows the progress icon
    const saving = screen.getByRole('button', { name: /^Enable guard on / });
    await waitFor(() => expect(saving).toBeDisabled());
    expect(saving).toHaveTextContent('progress_activity');
    release();
    await waitFor(() => expect(saving).toBeEnabled());
  });

  it('says in its tooltip that Disable stops the supervisor owning the nodes', async () => {
    const user = userEvent.setup();
    show(assignment({ enabled: true }));
    await user.hover(
      await screen.findByRole('button', { name: /^Disable guard on / })
    );
    expect(
      await screen.findByText(/stop this supervisor owning these nodes/)
    ).toHaveAttribute('data-slot', 'tooltip-content');
  });

  it('says in its tooltip that Enable gives the nodes back', async () => {
    const user = userEvent.setup();
    show(assignment({ enabled: false }));
    await user.hover(
      await screen.findByRole('button', { name: /^Enable guard on / })
    );
    expect(
      await screen.findByText(/let this supervisor own these nodes again/)
    ).toHaveAttribute('data-slot', 'tooltip-content');
  });

  it('shows the message when saving fails', async () => {
    const user = userEvent.setup();
    failPut = true;
    show(assignment({ enabled: false }));
    await user.click(
      await screen.findByRole('button', { name: /^Enable guard on / })
    );
    expect(await screen.findByText('The server said no')).toBeInTheDocument();
  });

  it('cannot change an assignment from the definition or the server, and says why', async () => {
    show(assignment({ id: 'd1', source: 'DEFINITION' }));
    expect(await screen.findByText('In the definition')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Disable / })).toBeNull();
    expect(screen.getByText('Whole workflow')).toBeInTheDocument();
  });

  it('shows the nodes with their owners, twelve first and the rest on request', async () => {
    const user = userEvent.setup();
    show(assignment({ source: 'SYSTEM' }));
    expect(await screen.findByText('Set by the server')).toBeInTheDocument();
    expect(await screen.findByText('Run level: guard')).toBeInTheDocument();
    expect(screen.getByText('a')).toBeInTheDocument();
    expect(screen.queryByText('n')).toBeNull();
    await user.click(screen.getByRole('button', { name: '+2 more' }));
    expect(screen.getByText('n')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /more$/ })).toBeNull();
  });
});
