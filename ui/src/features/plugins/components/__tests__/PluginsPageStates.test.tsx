// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  afterAll,
  afterEach,
  vi,
} from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { PluginsPage } from '../PluginsPage';
import { PluginCanvasPreview } from '../PluginCanvasPreview';
import { createTestQueryClient } from '@/test/utils/testUtils';
import type { PluginDetails } from '../../types';
import { nodeExample } from '../../lib/nodeExample';

const envelope = (data: unknown) => ({
  timestamp: 't',
  status: 200,
  message: 'ok',
  data,
  path: '/',
});

const server = setupServer();
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const renderPage = (url = '/plugins') =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <PluginsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('PluginsPage states', () => {
  it('shows an error when the list fails to load', async () => {
    server.use(
      http.get('/api/plugins', () => HttpResponse.json({}, { status: 500 }))
    );
    renderPage();
    expect(
      await screen.findByText('Failed to load plugins.')
    ).toBeInTheDocument();
  });

  it('shows an empty message when there are no plugins', async () => {
    server.use(http.get('/api/plugins', () => HttpResponse.json(envelope([]))));
    renderPage();
    expect(
      await screen.findByText('No plugins available.')
    ).toBeInTheDocument();
  });

  it('shows an error when the selected plugin fails to load', async () => {
    server.use(
      http.get('/api/plugins', () =>
        HttpResponse.json(envelope([{ type: 'Broken', category: 'PROCESSOR' }]))
      ),
      http.get('/api/plugins/:type', () =>
        HttpResponse.json({}, { status: 404 })
      )
    );
    renderPage('/plugins?type=Broken');
    expect(
      await screen.findByText('Failed to load Broken.')
    ).toBeInTheDocument();
  });
});

describe('PluginsPage details', () => {
  const serve = (details: object) =>
    server.use(
      http.get('/api/plugins', () =>
        HttpResponse.json(envelope([{ type: 'Sink', category: 'TERMINAL' }]))
      ),
      http.get('/api/plugins/:type', () =>
        HttpResponse.json(
          envelope({
            type: 'Sink',
            category: 'TERMINAL',
            description: null,
            usagePattern: null,
            uiDesign: null,
            outputPorts: [],
            ...details,
          })
        )
      )
    );

  it('says a terminal has no output ports', async () => {
    serve({});
    renderPage();
    expect(await screen.findByText('None (terminal)')).toBeInTheDocument();
  });

  it('lists declared output ports and the configuration fields', async () => {
    serve({
      outputPorts: ['ok', 'error'],
      configSchema: [
        {
          name: 'url',
          type: 'STRING',
          required: true,
          defaultValue: null,
          options: [],
          description: 'where to call',
        },
        {
          name: 'retries',
          type: 'NUMBER',
          required: false,
          defaultValue: 0,
          options: [],
          description: '',
        },
      ],
    });
    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByText('where to call')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^Configuration/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(screen.getByText('NUMBER')).toBeInTheDocument();
    expect(screen.getAllByText('*')).toHaveLength(1);
    // the defaults: none for url, 0 for retries
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByText('0')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: /^Ports/ }));
    expect(screen.getByText('ok, error')).toBeInTheDocument();
    expect(screen.queryByText('where to call')).not.toBeInTheDocument();
  });

  it('shows and copies a node built from the schema, with the usage under it', async () => {
    serve({
      usagePattern: 'usage-text',
      configSchema: [
        {
          name: 'url',
          type: 'STRING',
          required: true,
          defaultValue: null,
          options: [],
          description: '',
        },
        {
          name: 'tags',
          type: 'MAP',
          required: false,
          defaultValue: { a: 1 },
          options: [],
          description: '',
        },
      ],
    });
    const user = userEvent.setup();
    const write = vi.spyOn(navigator.clipboard, 'writeText');
    renderPage();
    expect(await screen.findByText('{"a":1}')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Example' }));
    expect(screen.getByText('usage-text')).toBeInTheDocument();
    const json = JSON.stringify(
      { nodeId: 'sink', type: 'Sink', config: { url: '', tags: { a: 1 } } },
      null,
      2
    );
    expect(
      screen.getByText(
        (_, node) => node?.tagName === 'PRE' && node.textContent === json
      )
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Copy node JSON' }));
    expect(write).toHaveBeenCalledWith(json);
    expect(await screen.findByRole('status')).toHaveTextContent('Copied');

    write.mockRejectedValueOnce(new Error('denied'));
    await user.click(screen.getByRole('button', { name: 'Copy node JSON' }));
    expect(await screen.findByText('Copy failed')).toBeInTheDocument();
  });

  it('omits the configuration section when the schema is empty', async () => {
    serve({ configSchema: [] });
    renderPage();
    await screen.findByText('None (terminal)');
    expect(screen.queryByText('Configuration')).not.toBeInTheDocument();
  });
});

describe('PluginCanvasPreview', () => {
  const base: PluginDetails = {
    type: 'Sink',
    category: 'TERMINAL',
    description: null,
    usagePattern: null,
    uiDesign: null,
    outputPorts: [],
  };

  it('renders the plugin as a canvas node', async () => {
    render(<PluginCanvasPreview plugin={base} />);
    expect(await screen.findByTestId('workflow-node-Sink')).toBeInTheDocument();
  });
});

describe('nodeExample', () => {
  const field = {
    required: true,
    defaultValue: null,
    options: [],
    description: '',
  };
  it('starts a required enum at its first option, and skips optional fields without a default', () => {
    const plugin: PluginDetails = {
      type: 'X',
      category: 'PROCESSOR',
      description: null,
      usagePattern: null,
      uiDesign: null,
      outputPorts: [],
      configSchema: [
        { ...field, name: 'mode', type: 'ENUM', options: ['FAST', 'SLOW'] },
        { ...field, name: 'kind', type: 'ENUM' },
        { ...field, name: 'args', type: 'LIST' },
        { ...field, name: 'note', type: 'STRING', required: false },
      ],
    };
    expect(nodeExample(plugin)).toEqual({
      nodeId: 'x',
      type: 'X',
      config: { mode: 'FAST', kind: '', args: [] },
    });
    expect(nodeExample({ ...plugin, configSchema: undefined }).config).toEqual(
      {}
    );
  });
});
