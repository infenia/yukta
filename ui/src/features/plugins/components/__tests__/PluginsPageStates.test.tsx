// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { PluginsPage } from '../PluginsPage';
import { PluginCanvasPreview } from '../PluginCanvasPreview';
import { createTestQueryClient } from '@/test/utils/testUtils';
import type { PluginDetails } from '../../types';

const envelope = (data: unknown) => ({ timestamp: 't', status: 200, message: 'ok', data, path: '/' });

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
    server.use(http.get('/api/plugins', () => HttpResponse.json({}, { status: 500 })));
    renderPage();
    expect(await screen.findByText('Failed to load plugins.')).toBeInTheDocument();
  });

  it('shows an empty message when there are no plugins', async () => {
    server.use(http.get('/api/plugins', () => HttpResponse.json(envelope([]))));
    renderPage();
    expect(await screen.findByText('No plugins available.')).toBeInTheDocument();
  });

  it('shows an error when the selected plugin fails to load', async () => {
    server.use(
      http.get('/api/plugins', () =>
        HttpResponse.json(envelope([{ type: 'Broken', category: 'PROCESSOR' }]))
      ),
      http.get('/api/plugins/:type', () => HttpResponse.json({}, { status: 404 }))
    );
    renderPage('/plugins?type=Broken');
    expect(await screen.findByText('Failed to load Broken.')).toBeInTheDocument();
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
    renderPage();
    expect(await screen.findByText('ok, error')).toBeInTheDocument();
    expect(screen.getByText('where to call')).toBeInTheDocument();
    expect(screen.getByText('NUMBER')).toBeInTheDocument();
    expect(screen.getAllByText('*')).toHaveLength(1);
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

  it('renders the plugin ui design html', async () => {
    const plugin = {
      ...base,
      uiDesign: { html: '<span>design-marker</span>', width: 300, height: 80 },
    };
    render(<PluginCanvasPreview plugin={plugin} />);
    expect(await screen.findByText('design-marker')).toBeInTheDocument();
  });
});
