// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  vi,
  beforeAll,
  afterAll,
  afterEach,
} from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { parsePayload, RunControls } from '../RunControls';

const calls: string[] = [];
let startStatus = 200;
let priorityStatus = 200;
let recoverStatus = 200;

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const server = setupServer(
  http.post('/api/workflow/start', () =>
    startStatus === 200
      ? ok({ executionId: 'new-run' })
      : HttpResponse.json({ message: 'Too busy' }, { status: startStatus })
  ),
  http.post('/api/workflow/:sid/:wid/stop', ({ request }) => {
    calls.push(`stop-all ${new URL(request.url).search}`);
    return ok({ executionIds: [] });
  }),
  http.post('/api/executions/:id/priority', ({ params, request }) => {
    calls.push(`priority ${params.id} ${new URL(request.url).search}`);
    return priorityStatus === 200
      ? ok({ executionId: String(params.id) })
      : HttpResponse.json(
          { message: 'No longer waiting' },
          { status: priorityStatus }
        );
  }),
  http.post('/api/executions/:id/recover', ({ params }) => {
    calls.push(`recover ${params.id}`);
    return recoverStatus === 200
      ? ok({ executionId: 'recovered' })
      : HttpResponse.json(
          { message: 'Already resumed' },
          { status: recoverStatus }
        );
  }),
  http.post('/api/executions/:id/:action', ({ params }) => {
    calls.push(`${params.action} ${params.id}`);
    return ok({ executionId: 'restarted' });
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  calls.length = 0;
  startStatus = 200;
  priorityStatus = 200;
  recoverStatus = 200;
});
afterAll(() => server.close());

function setup(status: string | null, executionId: string | null = 'e1') {
  const onStarted = vi.fn();
  renderWithProviders(
    <RunControls
      sessionId="s1"
      workflowId="wf"
      executionId={executionId}
      status={status}
      onStarted={onStarted}
    />
  );
  return onStarted;
}

describe('parsePayload', () => {
  it('treats empty text as no payload and accepts objects', () => {
    expect(parsePayload('  ')).toEqual({});
    expect(parsePayload('{"a":1}')).toEqual({ a: 1 });
  });

  it('rejects non-objects', () => {
    expect(() => parsePayload('[1]')).toThrow('JSON object');
    expect(() => parsePayload('null')).toThrow('JSON object');
    expect(() => parsePayload('{')).toThrow(SyntaxError);
  });
});

describe('RunControls', () => {
  it('only offers Run before any execution', () => {
    setup(null, null);
    expect(screen.getByRole('button', { name: 'Run workflow' })).toBeEnabled();
    // Pause, Stop and Restart are one group; Resume shows only while paused
    const group = screen.getByRole('group', { name: 'Run controls' });
    expect(
      within(group).getByRole('button', { name: /pause/i })
    ).toBeDisabled();
    expect(screen.queryByRole('button', { name: /resume/i })).toBeNull();
    expect(
      within(group).getByRole('button', { name: /stop options/i })
    ).toBeDisabled();
    expect(screen.getByRole('button', { name: /restart/i })).toBeDisabled();
  });

  it('pauses a running execution and resumes a paused one', async () => {
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(
      <RunControls
        sessionId="s1"
        workflowId="wf"
        executionId="e1"
        status="RUNNING"
        onStarted={vi.fn()}
      />
    );
    await user.click(screen.getByRole('button', { name: /pause/i }));
    await waitFor(() => expect(calls).toEqual(['pause e1']));
    unmount();

    setup('PAUSED');
    await user.click(screen.getByRole('button', { name: /resume/i }));
    await waitFor(() => expect(calls).toEqual(['pause e1', 'resume e1']));
  });

  it('restarts a finished execution and selects the new one', async () => {
    const user = userEvent.setup();
    const onStarted = setup('COMPLETED');
    await user.click(screen.getByRole('button', { name: /restart/i }));
    await waitFor(() => expect(onStarted).toHaveBeenCalledWith('restarted'));
    expect(calls).toEqual(['restart e1']);
  });

  it.each(['RUNNING', 'PENDING', 'COMPLETED'])(
    'hides Priority for a %s run',
    (status) => {
      setup(status);
      expect(
        screen.queryByRole('button', { name: /priority/i })
      ).not.toBeInTheDocument();
    }
  );

  it('changes the priority of a queued run', async () => {
    const user = userEvent.setup();
    setup('QUEUED');
    await user.click(screen.getByRole('button', { name: /priority/i }));
    const input = screen.getByLabelText(/Priority \(-100 to 100/);
    expect(input).toHaveValue(0);
    await user.clear(input);
    await user.type(input, '5');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(calls).toEqual(['priority e1 ?priority=5']));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('rejects a priority that is empty, fractional or out of range', async () => {
    const user = userEvent.setup();
    setup('QUEUED');
    await user.click(screen.getByRole('button', { name: /priority/i }));
    const input = screen.getByLabelText(/Priority \(-100 to 100/);
    for (const text of ['', '1.5', '101', '-101']) {
      await user.clear(input);
      if (text) await user.type(input, text);
      expect(screen.getByRole('alert')).toHaveTextContent(
        'whole number from -100 to 100'
      );
      expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled();
    }
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it('cancels the priority dialog and shows a refusal', async () => {
    priorityStatus = 409;
    const user = userEvent.setup();
    setup('QUEUED');
    await user.click(screen.getByRole('button', { name: /priority/i }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /priority/i }));
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('No longer waiting')).toBeInTheDocument();
  });

  it.each(['RUNNING', 'COMPLETED', 'FAILURE'])(
    'hides Recover for a %s run',
    (status) => {
      setup(status);
      expect(
        screen.queryByRole('button', { name: /recover/i })
      ).not.toBeInTheDocument();
    }
  );

  it('recovers an aborted run after confirmation and selects the new one', async () => {
    const user = userEvent.setup();
    const onStarted = setup('ABORTED');
    await user.click(screen.getByRole('button', { name: /recover/i }));
    expect(screen.getByText(/Finished nodes are replayed/)).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /recover/i }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(calls).toEqual([]);

    await user.click(screen.getByRole('button', { name: /recover/i }));
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', {
        name: 'Recover',
      })
    );
    await waitFor(() => expect(onStarted).toHaveBeenCalledWith('recovered'));
    expect(calls).toEqual(['recover e1']);
  });

  it('shows the server message when the run cannot be recovered', async () => {
    recoverStatus = 409;
    const user = userEvent.setup();
    const onStarted = setup('ABORTED');
    await user.click(screen.getByRole('button', { name: /recover/i }));
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', {
        name: 'Recover',
      })
    );
    expect(await screen.findByText('Already resumed')).toBeInTheDocument();
    expect(onStarted).not.toHaveBeenCalled();
  });

  it('confirms before stopping this run, and can cancel', async () => {
    const user = userEvent.setup();
    setup('RUNNING');

    await user.click(screen.getByRole('button', { name: /stop options/i }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Stop this run' })
    );
    expect(
      screen.getByRole('dialog', { name: 'Stop this run?' })
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(calls).toEqual([]);

    await user.click(screen.getByRole('button', { name: /stop options/i }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Stop this run' })
    );
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls).toEqual(['stop e1']));
  });

  it('closes the confirmation with Escape', async () => {
    const user = userEvent.setup();
    setup('RUNNING');
    await user.click(screen.getByRole('button', { name: /stop options/i }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Stop this run' })
    );
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it('stops every run with the chosen mode', async () => {
    const user = userEvent.setup();
    setup('RUNNING');
    await user.click(screen.getByRole('button', { name: /stop options/i }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Kill all runs' })
    );
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls).toEqual(['stop-all ?mode=KILL']));
  });

  it('starts a run with a parsed payload and closes the dialog', async () => {
    const user = userEvent.setup();
    const onStarted = setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    await user.click(screen.getByLabelText(/trigger payload/i));
    await user.paste('{"a":1}');
    await user.click(screen.getByRole('button', { name: 'Run' }));
    await waitFor(() => expect(onStarted).toHaveBeenCalledWith('new-run'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('starts with an empty payload', async () => {
    const user = userEvent.setup();
    const onStarted = setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    await user.click(screen.getByRole('button', { name: 'Run' }));
    await waitFor(() => expect(onStarted).toHaveBeenCalledWith('new-run'));
  });

  it('reports invalid JSON and non-object payloads without calling the server', async () => {
    const user = userEvent.setup();
    const onStarted = setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    const box = screen.getByLabelText(/trigger payload/i);

    await user.click(box);
    await user.paste('{');
    await user.click(screen.getByRole('button', { name: 'Run' }));
    expect(screen.getByRole('alert')).toHaveTextContent('not valid JSON');

    await user.clear(box);
    await user.click(box);
    await user.paste('[1]');
    await user.click(screen.getByRole('button', { name: 'Run' }));
    expect(screen.getByRole('alert')).toHaveTextContent(
      'must be a JSON object'
    );
    expect(onStarted).not.toHaveBeenCalled();
  });

  it('shows the server error when a start fails and the dialog can be cancelled', async () => {
    const user = userEvent.setup();
    startStatus = 429;
    setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    await user.click(screen.getByRole('button', { name: 'Run' }));
    expect((await screen.findAllByRole('alert'))[0]).toHaveTextContent(
      /scheduler/i
    );
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
