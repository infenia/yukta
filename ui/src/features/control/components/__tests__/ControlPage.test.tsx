// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
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

const server = setupServer(
  http.get('/api/supervisors', () => ok([])),
  http.get('/api/control/nodes', () =>
    nodesFail ? HttpResponse.json({ message: 'x' }, { status: 500 }) : ok(nodes)
  ),
  http.get('/api/control/workflows/:wf/nodes', () => ok(['wf-node-1'])),
  http.get('/api/control/workflows/:wf/nodes/:node/heartbeat', ({ params }) =>
    heartbeatFail
      ? HttpResponse.json({ message: 'none' }, { status: 404 })
      : ok({ nodeId: params.node, beat: 1 })
  )
);

beforeAll(() => server.listen());
afterEach(() => {
  nodes = [];
  nodesFail = false;
  heartbeatFail = false;
});
afterAll(() => server.close());

describe('ControlPage', () => {
  it('shows the empty state when no node is registered', async () => {
    renderWithProviders(<ControlPage />);
    expect(await screen.findByText('No active nodes.')).toBeInTheDocument();
  });

  it('lists the global nodes without heartbeat actions', async () => {
    nodes = ['a:n1', 'a:n2'];
    renderWithProviders(<ControlPage />);
    expect(await screen.findByText('a:n1')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /heartbeat/i })
    ).not.toBeInTheDocument();
  });

  it('reports a failure to load nodes', async () => {
    nodesFail = true;
    renderWithProviders(<ControlPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to load nodes'
    );
  });

  it('filters by workflow and toggles a node heartbeat', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await screen.findByText('No active nodes.');

    await user.type(screen.getByLabelText('Workflow id'), 'wf1');
    expect(await screen.findByText('wf-node-1')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Heartbeat' }));
    expect(await screen.findByText(/"beat": 1/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Hide heartbeat' }));
    expect(screen.queryByText(/"beat": 1/)).not.toBeInTheDocument();
  });

  it('says so when a node has no heartbeat', async () => {
    heartbeatFail = true;
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await user.type(screen.getByLabelText('Workflow id'), 'wf1');
    await user.click(await screen.findByRole('button', { name: 'Heartbeat' }));
    expect(await screen.findByText('No heartbeat')).toBeInTheDocument();
  });
});
