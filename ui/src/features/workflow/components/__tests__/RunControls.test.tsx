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
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { expectTooltip, renderWithProviders } from '@/test/utils/testUtils';
import { RunControls } from '../RunControls';
import { RunButton } from '../RunButton';
import {
  modifierLabel,
  parsePayload,
  payloadValues,
} from '../RunButton.helpers';

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

let definitionNodes: { nodeId: string; type: string; config: unknown }[] = [
  {
    nodeId: 'n',
    type: 't',
    config: { a: '${start.label}', b: '${start.opts.n:int}' },
  },
];
let definitionEdges: { source: string; target: string }[] = [];
let definitionStatus = 200;
let startBodies: unknown[] = [];

let runs: { executionId: string; status: string }[] = [];

const server = setupServer(
  http.get('/api/sessions/:sid/workflows/:wid', () =>
    definitionStatus === 200
      ? ok({ workflowId: 'wf', nodes: definitionNodes, edges: definitionEdges })
      : HttpResponse.json({ message: 'no' }, { status: definitionStatus })
  ),
  http.post('/api/workflow/start', async ({ request }) => {
    startBodies.push(await request.json());
    return startStatus === 200
      ? ok({ executionId: 'new-run' })
      : HttpResponse.json({ message: 'Too busy' }, { status: startStatus });
  }),
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
  http.get('/api/sessions/:sid/workflows/:wid/executions', () => ok(runs)),
  http.post('/api/executions/:id/:action', ({ params, request }) => {
    calls.push(`${params.action} ${params.id}${new URL(request.url).search}`);
    return ok({ executionId: 'restarted' });
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  runs = [];
  calls.length = 0;
  startBodies = [];
  definitionStatus = 200;
  definitionEdges = [];
  definitionNodes = [
    {
      nodeId: 'n',
      type: 't',
      config: { a: '${start.label}', b: '${start.opts.n:int}' },
    },
  ];
  startStatus = 200;
  priorityStatus = 200;
  recoverStatus = 200;
  server.resetHandlers();
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

describe('RunButton', () => {
  it('shows its label as a tooltip when icon only, and keeps the class it is given', async () => {
    renderWithProviders(
      <RunButton
        sessionId="s1"
        workflowId="w1"
        onStarted={vi.fn()}
        iconOnly
        label="Run check"
        className="custom-run"
      />
    );
    const button = screen.getByRole('button', { name: 'Run check' });
    expect(button).toHaveClass('custom-run');
    expect(button).not.toHaveTextContent('Run');
    await expectTooltip(button, 'Run check');
  });

  it('keeps the text label when it is not icon only', () => {
    renderWithProviders(
      <RunButton sessionId="s1" workflowId="w1" onStarted={vi.fn()} />
    );
    expect(
      screen.getByRole('button', { name: 'Run workflow' })
    ).toHaveTextContent('Run');
  });
});

describe('modifierLabel and payloadValues', () => {
  it('shows the Command symbol on Apple devices and Ctrl elsewhere', () => {
    expect(modifierLabel('Mozilla/5.0 (Macintosh; Intel Mac OS X)')).toBe('⌘');
    expect(modifierLabel('Mozilla/5.0 (iPhone; CPU iPhone OS)')).toBe('⌘');
    expect(modifierLabel('Mozilla/5.0 (X11; Linux x86_64)')).toBe('Ctrl');
  });

  it('leaves out inputs whose payload value is null', () => {
    const inputs = [
      { path: 'a', type: 'text' as const, optional: true },
      { path: 'b', type: 'number' as const, optional: true },
    ];
    expect(payloadValues(inputs, { a: null, b: 2 })).toEqual({ b: '2' });
  });
});

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

  it('stops only this run with the chosen mode', async () => {
    const user = userEvent.setup();
    setup('RUNNING');
    for (const [item, expected] of [
      ['Cancel this run', 'stop e1?mode=CANCEL'],
      ['Kill this run', 'stop e1?mode=KILL'],
    ]) {
      await user.click(screen.getByRole('button', { name: /stop options/i }));
      await user.click(await screen.findByRole('menuitem', { name: item }));
      expect(screen.getByRole('dialog')).toHaveTextContent(
        'stops only the run on screen'
      );
      await user.click(screen.getByRole('button', { name: 'Confirm' }));
      await waitFor(() => expect(calls).toContain(expected));
    }
  });

  it('names how many runs stopping all of them would hit', async () => {
    runs = [
      { executionId: 'a', status: 'RUNNING' },
      { executionId: 'b', status: 'QUEUED' },
      { executionId: 'c', status: 'SUCCESS' },
    ];
    const user = userEvent.setup();
    setup('RUNNING');
    await user.click(screen.getByRole('button', { name: /stop options/i }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Drain all runs' })
    );
    await waitFor(() =>
      expect(screen.getByRole('dialog')).toHaveTextContent(
        'stops 2 active runs of this workflow'
      )
    );
  });

  it('says one run in the singular', async () => {
    runs = [{ executionId: 'a', status: 'RUNNING' }];
    const user = userEvent.setup();
    setup('RUNNING');
    await user.click(screen.getByRole('button', { name: /stop options/i }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Kill all runs' })
    );
    await waitFor(() =>
      expect(screen.getByRole('dialog')).toHaveTextContent(
        'stops 1 active run of this workflow'
      )
    );
  });

  it('says every active run when the list could not be read', async () => {
    server.use(
      http.get('/api/sessions/:sid/workflows/:wid/executions', () =>
        HttpResponse.json({ message: 'x' }, { status: 500 })
      )
    );
    const user = userEvent.setup();
    setup('RUNNING');
    await user.click(screen.getByRole('button', { name: /stop options/i }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Cancel all runs' })
    );
    expect(await screen.findByRole('dialog')).toHaveTextContent(
      'stops every active run of this workflow'
    );
  });

  it('starts a run with a parsed payload and closes the dialog', async () => {
    const user = userEvent.setup();
    const onStarted = setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    await user.click(await screen.findByRole('button', { name: '{} JSON' }));
    await user.clear(screen.getByLabelText(/trigger payload/i));
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
    await user.click(await screen.findByRole('button', { name: 'Run' }));
    await waitFor(() => expect(onStarted).toHaveBeenCalledWith('new-run'));
    expect(startBodies).toHaveLength(1);
  });

  it('builds the payload from the form, typed and nested, and marks what is not set', async () => {
    const user = userEvent.setup();
    setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    await user.type(await screen.findByLabelText('label'), 'lint');
    expect(screen.getByText('1 not set')).toBeInTheDocument();
    await user.type(screen.getByLabelText('opts.n'), '3');
    expect(screen.queryByText(/not set/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Run' }));
    await waitFor(() => expect(startBodies).toHaveLength(1));
    expect(JSON.stringify(startBodies[0])).toContain(
      '"payload":{"label":"lint","opts":{"n":3}}'
    );
  });

  it('carries the form values to the JSON editor and back', async () => {
    const user = userEvent.setup();
    setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    await user.type(await screen.findByLabelText('label'), 'lint');
    await user.click(screen.getByRole('button', { name: '{} JSON' }));
    expect(screen.getByLabelText(/trigger payload/i)).toHaveValue(
      '{\n  "label": "lint",\n  "opts": {\n    "n": 0\n  }\n}'
    );
    await user.click(screen.getByRole('button', { name: 'Form' }));
    expect(screen.getByLabelText('label')).toHaveValue('lint');
  });

  it('sends a toggle from the form as a boolean', async () => {
    const user = userEvent.setup();
    definitionNodes = [
      { nodeId: 'n', type: 't', config: { a: '${start.on:bool}' } },
    ];
    setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    await user.selectOptions(await screen.findByLabelText('on'), 'On');
    await user.click(screen.getByRole('button', { name: 'Run' }));
    await waitFor(() => expect(startBodies).toHaveLength(1));
    expect(JSON.stringify(startBodies[0])).toContain('"payload":{"on":true}');
  });

  it('reports a payload that is not valid JSON when switching back to the form', async () => {
    const user = userEvent.setup();
    setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    await user.click(await screen.findByRole('button', { name: '{} JSON' }));
    await user.click(screen.getByLabelText(/trigger payload/i));
    await user.paste('{');
    await user.click(screen.getByRole('button', { name: 'Form' }));
    expect(screen.getByRole('alert')).toHaveTextContent('not valid JSON');
    expect(screen.getByLabelText(/trigger payload/i)).toBeInTheDocument();
  });

  it('shows an optional payload input with its default, and does not count it as not set', async () => {
    const user = userEvent.setup();
    definitionNodes = [
      { nodeId: 'in', type: 'API_TRIGGER', config: {} },
      {
        nodeId: 'm',
        type: 'MAPPER',
        config: { t: "payload['text'] != null ? payload['text'] : 'Yukta'" },
      },
    ];
    definitionEdges = [{ source: 'in', target: 'm' }];
    setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    expect(await screen.findByLabelText('text')).toHaveAttribute(
      'placeholder',
      'Yukta'
    );
    expect(screen.getByRole('img', { name: 'Optional' })).toBeVisible();
    expect(screen.queryByText(/not set/)).not.toBeInTheDocument();
  });

  it('starts the JSON editor with every input as a key, and submits on Enter and Ctrl+Enter', async () => {
    const user = userEvent.setup();
    const onStarted = setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    await user.type(await screen.findByLabelText('label'), 'lint{Enter}');
    await waitFor(() => expect(onStarted).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    await user.click(await screen.findByRole('button', { name: '{} JSON' }));
    expect(
      JSON.parse(
        (screen.getByLabelText(/trigger payload/i) as HTMLTextAreaElement).value
      )
    ).toEqual({
      label: '',
      opts: { n: 0 },
    });
    await user.click(screen.getByLabelText(/trigger payload/i));
    await user.keyboard('{Control>}{Enter}{/Control}');
    await waitFor(() => expect(onStarted).toHaveBeenCalledTimes(2));
  });

  it('does not submit again while the start is pending', async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.post('/api/workflow/start', async ({ request }) => {
        startBodies.push(await request.json());
        await gate;
        return ok({ executionId: 'held' });
      })
    );
    const onStarted = setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    const field = await screen.findByLabelText('label');
    await user.type(field, 'lint{Enter}');
    fireEvent.submit(field.closest('form') as HTMLFormElement);
    await user.keyboard('{Control>}{Enter}{/Control}');
    release();
    await waitFor(() => expect(onStarted).toHaveBeenCalledTimes(1));
    expect(startBodies).toHaveLength(1);
  });

  it('says no inputs are needed, and still offers the JSON editor', async () => {
    const user = userEvent.setup();
    definitionNodes = [{ nodeId: 'n', type: 't', config: { a: 1 } }];
    const onStarted = setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    expect(await screen.findByText('No inputs needed')).toBeVisible();
    await user.click(screen.getByRole('button', { name: '{} JSON' }));
    expect(screen.getByLabelText(/trigger payload/i)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Run' }));
    await waitFor(() => expect(onStarted).toHaveBeenCalledWith('new-run'));
  });

  it('falls back to the JSON editor when the definition cannot be read', async () => {
    const user = userEvent.setup();
    definitionStatus = 500;
    setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    expect(await screen.findByLabelText(/trigger payload/i)).toBeVisible();
    expect(
      screen.queryByRole('button', { name: 'Form' })
    ).not.toBeInTheDocument();
  });

  it('reports invalid JSON and non-object payloads without calling the server', async () => {
    const user = userEvent.setup();
    const onStarted = setup(null, null);
    await user.click(screen.getByRole('button', { name: 'Run workflow' }));
    await user.click(await screen.findByRole('button', { name: '{} JSON' }));
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
