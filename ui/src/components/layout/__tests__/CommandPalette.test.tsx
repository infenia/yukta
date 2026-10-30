// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { createMockSession } from '@/test/factories/sessionFactory';
import { CommandPalette, openCommandPalette } from '../CommandPalette';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const runQueries: string[] = [];

const server = setupServer(
  http.get('/api/sessions/summaries', () =>
    ok([createMockSession({ sessionId: 'dogfood', name: 'dogfood' })])
  ),
  http.get('/api/sessions/dogfood/workflows', () =>
    ok([
      {
        workflowId: 'check',
        description: '',
        nodeCount: 100,
        edgeCount: 124,
        status: 'RUNNING',
      },
      {
        workflowId: 'tool',
        description: '',
        nodeCount: 4,
        edgeCount: 3,
        status: null,
      },
    ])
  ),
  http.get('/api/executions', ({ request }) => {
    runQueries.push(new URL(request.url).searchParams.get('text') ?? '');
    return ok([
      {
        sessionId: 'dogfood',
        executionId: 'a3f19c02-0000-4000-8000-000000000000',
        workflowId: 'check',
        status: 'SUCCESS',
        startTime: null,
        endTime: null,
      },
    ]);
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  runQueries.length = 0;
  localStorage.clear();
});
afterAll(() => server.close());

function Where() {
  const location = useLocation();
  return (
    <output data-testid="where">{location.pathname + location.search}</output>
  );
}

function renderPalette() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={['/']}>
        <Where />
        <CommandPalette />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

const input = () => screen.getByRole('combobox');

describe('CommandPalette', () => {
  it('opens on Ctrl+K and closes on Escape', async () => {
    const user = userEvent.setup();
    renderPalette();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.keyboard('{Control>}k{/Control}');
    expect(
      screen.getByRole('dialog', { name: 'Command palette' })
    ).toBeInTheDocument();
    expect(input()).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('opens from the header button through openCommandPalette', () => {
    renderPalette();
    act(() => openCommandPalette());
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('lists workflows with their status and opens the one picked', async () => {
    const user = userEvent.setup();
    renderPalette();
    act(() => openCommandPalette());

    const check = await screen.findByTestId('palette-workflow:dogfood/check');
    expect(check).toHaveTextContent('in dogfood · 100 nodes');
    expect(check).toHaveTextContent('Running');

    await user.type(input(), 'check');
    await waitFor(() =>
      expect(screen.getAllByRole('option')[0]).toHaveAttribute(
        'data-testid',
        'palette-workflow:dogfood/check'
      )
    );
    expect(
      screen.queryByTestId('palette-workflow:dogfood/tool')
    ).not.toBeInTheDocument();

    await user.keyboard('{Enter}');
    expect(screen.getByTestId('where')).toHaveTextContent(
      '/sessions/dogfood/workflow/check'
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('moves the selection with the arrow keys, wrapping at the ends', async () => {
    const user = userEvent.setup();
    renderPalette();
    act(() => openCommandPalette());
    await screen.findByTestId('palette-workflow:dogfood/check');

    const selected = () =>
      screen
        .getAllByRole('option')
        .findIndex((o) => o.getAttribute('aria-selected') === 'true');
    expect(selected()).toBe(0);
    await user.keyboard('{ArrowDown}');
    expect(selected()).toBe(1);
    await user.keyboard('{ArrowUp}{ArrowUp}');
    expect(selected()).toBe(screen.getAllByRole('option').length - 1);
  });

  it('searches runs by ID once the query is long enough and deep-links to the run', async () => {
    const user = userEvent.setup();
    renderPalette();
    act(() => openCommandPalette());

    await user.type(input(), 'a3');
    expect(runQueries).toEqual([]);

    await user.type(input(), 'f');
    const run = await screen.findByTestId(
      'palette-run:a3f19c02-0000-4000-8000-000000000000'
    );
    expect(run).toHaveTextContent('a3f19c02');
    expect(runQueries).toContain('a3f');

    await user.click(run);
    expect(screen.getByTestId('where')).toHaveTextContent(
      '/sessions/dogfood/workflow/check?exec=a3f19c02-0000-4000-8000-000000000000'
    );
  });

  it('says so when nothing matches', async () => {
    const user = userEvent.setup();
    server.use(http.get('/api/executions', () => ok([])));
    renderPalette();
    act(() => openCommandPalette());

    await user.type(input(), 'zzzz');
    expect(
      await screen.findByText('Nothing matches “zzzz”.')
    ).toBeInTheDocument();
  });

  it('switches the theme from an action', async () => {
    const user = userEvent.setup();
    renderPalette();
    act(() => openCommandPalette());

    await user.type(input(), 'dark theme');
    await user.keyboard('{Enter}');
    expect(localStorage.getItem('theme')).toBe('dark');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('offers the light theme while the theme is dark', async () => {
    const user = userEvent.setup();
    localStorage.setItem('theme', 'dark');
    renderPalette();
    act(() => openCommandPalette());
    await user.type(input(), 'light theme');
    await user.keyboard('{Enter}');
    expect(localStorage.getItem('theme')).toBe('light');
  });

  it('copies the link to the current page, and still closes without a clipboard', async () => {
    const user = userEvent.setup();
    renderPalette();
    act(() => openCommandPalette());
    await user.type(input(), 'copy link');
    await user.keyboard('{Enter}');
    expect(await navigator.clipboard.readText()).toBe(window.location.href);

    const clipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    Object.defineProperty(navigator, 'clipboard', {
      value: undefined,
      configurable: true,
    });
    try {
      act(() => openCommandPalette());
      await user.type(input(), 'copy link');
      await user.keyboard('{Enter}');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    } finally {
      if (clipboard) Object.defineProperty(navigator, 'clipboard', clipboard);
    }
  });

  it('names a session without a name by its ID', async () => {
    server.use(
      http.get('/api/sessions/summaries', () =>
        ok([createMockSession({ sessionId: 'nameless', name: '' })])
      ),
      http.get('/api/sessions/nameless/workflows', () => ok([]))
    );
    renderPalette();
    act(() => openCommandPalette());
    expect(
      await screen.findByTestId('palette-session:nameless')
    ).toHaveTextContent('nameless');
  });

  it('keeps the arrow keys and Enter harmless when nothing matches', async () => {
    const user = userEvent.setup();
    server.use(http.get('/api/executions', () => ok([])));
    renderPalette();
    act(() => openCommandPalette());
    await user.type(input(), 'zzzz');
    await screen.findByText('Nothing matches “zzzz”.');
    await user.keyboard('{ArrowDown}{Enter}');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(input()).not.toHaveAttribute('aria-activedescendant');
  });

  it('closes when the backdrop is clicked', async () => {
    const user = userEvent.setup();
    renderPalette();
    act(() => openCommandPalette());
    const backdrop = screen.getByRole('dialog').parentElement as HTMLElement;
    await user.pointer({ keys: '[MouseLeft]', target: backdrop });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
