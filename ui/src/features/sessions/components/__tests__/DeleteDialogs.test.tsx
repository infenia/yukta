// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { Route, Routes, useLocation } from 'react-router';
import { renderWithProviders } from '@/test/utils/testUtils';
import { DeleteSessionDialog, DeleteWorkflowDialog } from '../DeleteDialogs';

const calls: string[] = [];
let busy = false;
const refuse = (search: string) =>
  busy && search === ''
    ? HttpResponse.json(
        { message: '1 run(s) are still active: run-1. Stop them first, or delete with force=true to cancel them.' },
        { status: 409 }
      )
    : new HttpResponse(null, { status: 204 });
const server = setupServer(
  http.delete('/api/sessions/:sid', ({ request }) => {
    const { search } = new URL(request.url);
    calls.push(`session ${search}`);
    return refuse(search);
  }),
  http.delete('/api/sessions/:sid/workflows/:wid', ({ params, request }) => {
    const { search } = new URL(request.url);
    calls.push(`workflow ${String(params.wid)} ${search}`);
    return refuse(search);
  })
);
beforeAll(() => server.listen());
afterEach(() => {
  calls.length = 0;
  busy = false;
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

function Where() {
  return <output data-testid="where">{useLocation().pathname}</output>;
}

describe('delete dialogs', () => {
  it('delete a session, then leave for the session list', async () => {
    window.history.pushState({}, '', '/sessions/s1');
    const onClose = vi.fn();
    renderWithProviders(
      <Routes>
        <Route path="*" element={<><Where /><DeleteSessionDialog sessionId="s1" onClose={onClose} /></>} />
      </Routes>
    );
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Type s1 to confirm'), 's1');
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await user.click(await screen.findByRole('button', { name: 'Done' }));

    expect(calls).toEqual(['session ']);
    expect(screen.getByTestId('where')).toHaveTextContent('/');
    expect(onClose).toHaveBeenCalled();
  });

  it('delete a workflow, then open its session', async () => {
    window.history.pushState({}, '', '/sessions/s 1/workflow/wf');
    renderWithProviders(
      <Routes>
        <Route path="*" element={<><Where /><DeleteWorkflowDialog sessionId="s 1" workflowId="wf" onClose={vi.fn()} /></>} />
      </Routes>
    );
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Type wf to confirm'), 'wf');
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await user.click(await screen.findByRole('button', { name: 'Done' }));

    expect(calls).toEqual(['workflow wf ']);
    expect(screen.getByTestId('where')).toHaveTextContent('/sessions/s%201');
  });

  it('shows the blocking run with a link, then cancels the runs and deletes with force', async () => {
    busy = true;
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="*" element={<><Where /><DeleteSessionDialog sessionId="s1" onClose={vi.fn()} /></>} />
      </Routes>
    );
    await user.type(screen.getByLabelText('Type s1 to confirm'), 's1');
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    expect(await screen.findByRole('link', { name: 'run-1' })).toHaveAttribute('href', '/runs?text=run-1');
    await user.click(screen.getByRole('button', { name: 'Stop runs and delete' }));
    await user.click(screen.getByRole('button', { name: 'Yes, stop runs and delete' }));
    expect(await screen.findByRole('button', { name: 'Done' })).toBeInTheDocument();
    expect(calls).toEqual(['session ', 'session ?force=true']);
  });

  it('does the same for a workflow', async () => {
    busy = true;
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="*" element={<DeleteWorkflowDialog sessionId="s1" workflowId="wf" onClose={vi.fn()} />} />
      </Routes>
    );
    await user.type(screen.getByLabelText('Type wf to confirm'), 'wf');
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    expect(await screen.findByRole('link', { name: 'run-1' })).toHaveAttribute('href', '/runs?text=run-1');
    await user.click(screen.getByRole('button', { name: 'Stop runs and delete' }));
    await user.click(screen.getByRole('button', { name: 'Yes, stop runs and delete' }));
    expect(await screen.findByRole('button', { name: 'Done' })).toBeInTheDocument();
    expect(calls).toEqual(['workflow wf ', 'workflow wf ?force=true']);
  });
});
