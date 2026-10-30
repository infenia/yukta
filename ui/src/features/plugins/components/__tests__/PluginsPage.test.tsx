// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
        { type: 'OneShotTrigger', category: 'TRIGGER' },
        { type: 'ShellProcessor', category: 'PROCESSOR' },
      ])
    )
  ),
  http.get('/api/plugins/:type', ({ params }) =>
    HttpResponse.json(
      envelope({
        type: params.type,
        category: params.type === 'OneShotTrigger' ? 'TRIGGER' : 'PROCESSOR',
        description: `About ${params.type}`,
        usagePattern: 'usage-text',
        uiDesign: null,
        outputPorts: [],
      })
    )
  )
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('PluginsPage', () => {
  it('lists plugins by category, shows details and canvas preview, and filters', async () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <PluginsPage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(await screen.findByText('About OneShotTrigger')).toBeInTheDocument();
    expect(
      await screen.findByTestId('plugin-canvas-preview')
    ).toBeInTheDocument();

    // each entry reads as a name, with the plugin ID under it
    const shell = screen.getByRole('button', { name: /ShellProcessor/ });
    expect(shell).toHaveTextContent('Shellprocessor');
    await userEvent.click(shell);
    expect(await screen.findByText('About ShellProcessor')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { level: 2, name: 'Shellprocessor' })
    ).toBeInTheDocument();
    expect(screen.getByText('Processor')).toBeInTheDocument();
    // a catalog preview has no run, so its node shows no status
    expect(screen.getByTestId('plugin-canvas-preview')).not.toHaveTextContent(
      /Succeeded|Waiting/
    );

    await userEvent.type(screen.getByLabelText('Filter plugins'), 'oneshot');
    expect(
      screen.queryByRole('button', { name: /ShellProcessor/ })
    ).not.toBeInTheDocument();

    // the readable name matches too
    await userEvent.clear(screen.getByLabelText('Filter plugins'));
    await userEvent.type(screen.getByLabelText('Filter plugins'), 'shellproc');
    expect(
      screen.getByRole('button', { name: /ShellProcessor/ })
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /OneShotTrigger/ })
    ).not.toBeInTheDocument();
  });
});
