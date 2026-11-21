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
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { NodeInspector } from '../NodeInspector';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const server = setupServer(
  http.get('/api/plugins/:type', ({ params }) =>
    ok({
      type: params.type,
      category: 'PROCESSOR',
      description: params.type === 'HTTP_REQUEST' ? 'Calls a web service' : '',
      usagePattern: null,
      uiDesign: null,
      outputPorts: ['default'],
    })
  )
);
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const node = (type: string) => ({ nodeId: 'n1', type, config: {} });
const render = (type: string, onRename?: (nodeId: string) => string | null) =>
  renderWithProviders(
    <NodeInspector
      node={node(type)}
      onChange={vi.fn()}
      onRemove={vi.fn()}
      onGroupChange={vi.fn()}
      onRename={onRename}
    />
  );

describe('NodeInspector header', () => {
  it('shows the plugin icon, its name and what it does', async () => {
    render('HTTP_REQUEST');
    expect(await screen.findByText('Calls a web service')).toBeInTheDocument();
    const header = screen.getByRole('banner', { hidden: true });
    expect(within(header).getByText('Http Request.')).toBeInTheDocument();
    expect(within(header).getByText('http')).toBeInTheDocument();
  });

  it('shows no description line when the plugin has none', async () => {
    render('PLAIN');
    await screen.findByLabelText('Configuration (JSON)');
    expect(screen.queryByText('Plain.')).toBeNull();
  });
});

describe('NodeInspector node ID', () => {
  it('has no field to rename the node unless it can be renamed', async () => {
    render('PLAIN');
    await screen.findByLabelText('Configuration (JSON)');
    expect(screen.queryByLabelText('Node ID')).toBeNull();
  });

  it('renames when the user presses Enter or leaves the field', async () => {
    const user = userEvent.setup();
    const onRename = vi.fn().mockReturnValue(null);
    render('PLAIN', onRename);
    const field = screen.getByLabelText('Node ID');
    expect(field).toHaveValue('n1');

    await user.clear(field);
    await user.type(field, 'fetch{Enter}');
    expect(onRename).toHaveBeenLastCalledWith('fetch');

    await user.type(field, '2');
    await user.tab();
    expect(onRename).toHaveBeenLastCalledWith('fetch2');
    expect(field).toHaveAttribute('aria-invalid', 'false');
  });

  it('says what is wrong with an ID, and Escape goes back to the current one', async () => {
    const user = userEvent.setup();
    const onRename = vi.fn().mockReturnValue('Another node already has it.');
    render('PLAIN', onRename);
    const field = screen.getByLabelText('Node ID');

    await user.clear(field);
    await user.type(field, 'taken{Enter}');
    expect(
      screen.getByText('Another node already has it.')
    ).toBeInTheDocument();
    expect(field).toHaveAttribute('aria-invalid', 'true');

    await user.type(field, '{Escape}');
    expect(field).toHaveValue('n1');
    expect(screen.queryByText('Another node already has it.')).toBeNull();
    expect(
      screen.getByText(/An expression that mentions the old ID/)
    ).toBeInTheDocument();
  });
});
