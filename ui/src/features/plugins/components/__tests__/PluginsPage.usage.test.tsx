// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { delay, http, HttpResponse } from 'msw';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { PluginsPage } from '../PluginsPage';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

let usage: () => Response | Promise<Response> = () =>
  ok([
    { sessionId: 'dogfood', workflowId: 'check', nodes: 27 },
    { sessionId: 'dogfood', workflowId: 'tool', nodes: 1 },
  ]);

const server = setupServer(
  http.get('/api/plugins', () =>
    ok([{ type: 'PROCESS_EXECUTOR', category: 'PROCESSOR' }])
  ),
  http.get('/api/plugins/:type', () =>
    ok({
      type: 'PROCESS_EXECUTOR',
      category: 'PROCESSOR',
      description: null,
      usagePattern: null,
      uiDesign: null,
      outputPorts: [],
    })
  ),
  http.get('/api/plugins/:type/usage', () => usage())
);
beforeAll(() => server.listen());
afterEach(() => {
  usage = () => ok([]);
});
afterAll(() => server.close());

const open = async () => {
  const user = userEvent.setup();
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <PluginsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
  await user.click(await screen.findByRole('tab', { name: /^Used in/ }));
};

describe('PluginsPage Used in', () => {
  it('lists the workflows that use the plugin, with their node counts and links', async () => {
    await open();
    expect(
      await screen.findByRole('link', { name: 'dogfood / check' })
    ).toHaveAttribute('href', '/sessions/dogfood/workflow/check');
    expect(screen.getByText('27 nodes')).toBeInTheDocument();
    expect(screen.getByText('1 node')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Used in2' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
  });

  it('says when nothing uses it', async () => {
    await open();
    expect(
      await screen.findByText('No workflow uses PROCESS_EXECUTOR yet.')
    ).toBeInTheDocument();
  });

  it('reports a failed lookup', async () => {
    usage = () =>
      HttpResponse.json(
        { message: 'down' },
        { status: 500 }
      ) as unknown as Response;
    await open();
    expect(
      await screen.findByText('Failed to load where it is used.')
    ).toBeInTheDocument();
  });

  it('shows that it is still looking', async () => {
    usage = async () => {
      await delay('infinite');
      return ok([]);
    };
    await open();
    expect(screen.getAllByText('Loading…').length).toBeGreaterThan(0);
  });
});
