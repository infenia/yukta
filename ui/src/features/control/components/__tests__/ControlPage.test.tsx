// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { ControlPage } from '../ControlPage';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

let nodes: string[] = [];
let nodesFail = false;
let heartbeatFail = false;
let commandStatus = 200;
let commandBody: unknown;
let commandReply: unknown = { payload: { done: true } };

const server = setupServer(
  http.get('/api/supervisors', () => ok([])),
  http.get('/api/control/audit', () => ok([])),
  http.get('/api/workers', () => ok([])),
  http.get('/api/edition', () =>
    ok({ edition: 'PLUS', license: 'x', features: [], source: null })
  ),
  http.get('/api/control/nodes', () =>
    nodesFail ? HttpResponse.json({ message: 'x' }, { status: 500 }) : ok(nodes)
  ),
  http.get('/api/control/workflows/:wf/nodes/:node/heartbeat', ({ params }) =>
    heartbeatFail
      ? HttpResponse.json({ message: 'none' }, { status: 404 })
      : ok({ nodeId: params.node, beat: 1 })
  ),
  http.post(
    '/api/control/workflows/:wf/nodes/:node/command',
    async ({ request }) => {
      commandBody = await request.json();
      return commandStatus === 200
        ? ok(commandReply)
        : HttpResponse.json({ message: 'denied' }, { status: commandStatus });
    }
  )
);

beforeAll(() => server.listen());
afterEach(() => {
  nodes = [];
  nodesFail = false;
  heartbeatFail = false;
  commandStatus = 200;
  commandBody = undefined;
  commandReply = { payload: { done: true } };
});
afterAll(() => server.close());

async function pick(user: ReturnType<typeof userEvent.setup>) {
  await user.selectOptions(await screen.findByLabelText('Workflow'), 'a');
  await user.selectOptions(screen.getByLabelText('Node'), 'n:1');
}

describe('ControlPage', () => {
  it('shows the empty state when no node is registered', async () => {
    renderWithProviders(<ControlPage />);
    expect(await screen.findByText('No active nodes.')).toBeInTheDocument();
  });

  it('reports a failure to load nodes and retries', async () => {
    nodesFail = true;
    renderWithProviders(<ControlPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to load nodes'
    );

    nodesFail = false;
    nodes = ['a:n1'];
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: 'Retry' }));
    expect(
      await screen.findByRole('option', { name: 'a' })
    ).toBeInTheDocument();
  });

  it('shows the heartbeat as soon as a node is chosen; a node id keeps its colons', async () => {
    nodes = ['a:n:1', 'b:other'];
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await user.selectOptions(await screen.findByLabelText('Workflow'), 'a');
    expect(screen.getByLabelText('Node')).toHaveDisplayValue(
      'Choose a node…'
    );
    await user.selectOptions(screen.getByLabelText('Node'), 'n:1');

    expect(await screen.findByText(/"nodeId": "n:1"/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send command…' })).toBeEnabled();
  });

  it('says so when a node has no heartbeat', async () => {
    heartbeatFail = true;
    nodes = ['a:n:1'];
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await pick(user);
    expect(await screen.findByText('No heartbeat')).toBeInTheDocument();
  });

  it('sends a command and shows the response with a link to the audit log', async () => {
    nodes = ['a:n:1'];
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await pick(user);
    await user.click(screen.getByRole('button', { name: 'Send command…' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('recorded in the audit log');

    const editor = screen.getByLabelText('Command (JSON object)');
    await user.clear(editor);
    await user.click(editor);
    await user.paste('{"action":"flush"}');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(await screen.findByText(/"done": true/)).toBeInTheDocument();
    expect(commandBody).toEqual({ action: 'flush' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Find it in the audit log' })
    ).toHaveAttribute('href', '#audit-heading');
  });

  it('shows null when the node answers with no payload', async () => {
    nodes = ['a:n:1'];
    commandReply = {};
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await pick(user);
    await user.click(screen.getByRole('button', { name: 'Send command…' }));
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(await screen.findByText('null')).toBeInTheDocument();
  });

  it('refuses a command that is not valid before sending it', async () => {
    nodes = ['a:n:1'];
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await pick(user);
    await user.click(screen.getByRole('button', { name: 'Send command…' }));
    const editor = screen.getByLabelText('Command (JSON object)');
    const submit = async (text: string) => {
      await user.clear(editor);
      await user.click(editor);
      await user.paste(text);
      await user.click(screen.getByRole('button', { name: 'Send' }));
    };

    await submit('{nope');
    expect(await screen.findByRole('alert')).toHaveTextContent('not valid JSON');
    await submit('[1]');
    expect(screen.getByRole('alert')).toHaveTextContent('must be a JSON object');
    await submit('{}');
    expect(screen.getByRole('alert')).toHaveTextContent('must not be empty');
    await submit(
      JSON.stringify(Object.fromEntries(Array.from({ length: 101 }, (_, i) => [`k${i}`, 1])))
    );
    expect(screen.getByRole('alert')).toHaveTextContent('101 keys');
    expect(commandBody).toBeUndefined();
  });

  it('says an ADMIN key is needed on a 403, and shows other failures', async () => {
    nodes = ['a:n:1'];
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await pick(user);
    await user.click(screen.getByRole('button', { name: 'Send command…' }));
    const send = async () => {
      await user.click(screen.getByRole('button', { name: 'Send' }));
    };

    commandStatus = 403;
    await send();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Sending commands needs an ADMIN key.'
    );
    commandStatus = 409;
    await send();
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('denied')
    );

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
