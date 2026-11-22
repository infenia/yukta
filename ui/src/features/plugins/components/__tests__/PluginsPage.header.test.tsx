// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { PluginsPage } from '../PluginsPage';
import { createTestQueryClient } from '@/test/utils/testUtils';

const envelope = (data: unknown) => ({
  timestamp: 't',
  status: 200,
  message: 'ok',
  data,
  path: '/',
});

const server = setupServer(
  http.get('/api/plugins', () =>
    HttpResponse.json(
      envelope([
        {
          type: 'ShellProcessor',
          category: 'PROCESSOR',
          tier: 'CORE',
          restricted: true,
        },
      ])
    )
  ),
  http.get('/api/plugins/:type', ({ params }) =>
    HttpResponse.json(
      envelope({
        type: params.type,
        category: 'PROCESSOR',
        tier: 'CORE',
        restricted: true,
        description: 'About the shell',
        usagePattern: 'usage-text',
        uiDesign: null,
        outputPorts: [],
      })
    )
  )
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('PluginsPage header', () => {
  it('shows the tier in title case and marks a restricted plugin', async () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <PluginsPage />
        </MemoryRouter>
      </QueryClientProvider>
    );
    expect(await screen.findByText('Core')).toBeInTheDocument();
    expect(screen.getByText('Restricted')).toBeInTheDocument();
  });

  it('shows no tier and no badge for a plugin that has neither', async () => {
    const plain = {
      type: 'ShellProcessor',
      category: 'PROCESSOR',
      tier: null,
      restricted: false,
    };
    server.use(
      http.get('/api/plugins', () => HttpResponse.json(envelope([plain]))),
      http.get('/api/plugins/:type', () =>
        HttpResponse.json(
          envelope({
            ...plain,
            description: 'About the shell',
            usagePattern: 'usage-text',
            uiDesign: null,
            outputPorts: [],
          })
        )
      )
    );
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <PluginsPage />
        </MemoryRouter>
      </QueryClientProvider>
    );
    expect(await screen.findByRole('article')).toBeInTheDocument();
    expect(screen.queryByText('Restricted')).not.toBeInTheDocument();
    expect(screen.queryByText('Core')).not.toBeInTheDocument();
  });
});
