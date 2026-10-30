// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { setMe, type Me } from '@/lib/identity';
import { CommandPalette } from '../CommandPalette';
import { openCommandPalette } from '../CommandPalette.helpers';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const run = (executionId: string, status: string) => ({
  executionId,
  workflowId: 'check',
  status,
  startTime: '2026-10-30T08:00:00',
  endTime: null,
});
let runs = [run('e-new-0001', 'SUCCESS'), run('e-live-0002', 'RUNNING')];
const stopped: string[] = [];
let served = 0;

const server = setupServer(
  http.get('/api/sessions/summaries', () => ok([])),
  http.get('/api/executions', () => ok([])),
  http.get('/api/sessions/dogfood/workflows/check/executions', () => {
    served++;
    return ok(runs);
  }),
  http.post('/api/executions/:id/stop', ({ params }) => {
    stopped.push(params.id as string);
    return ok({ executionId: params.id });
  })
);
beforeAll(() => server.listen());
afterEach(() => {
  runs = [run('e-new-0001', 'SUCCESS'), run('e-live-0002', 'RUNNING')];
  stopped.length = 0;
  served = 0;
  setMe(null);
});
afterAll(() => server.close());

function open(url: string) {
  const user = userEvent.setup();
  const written: string[] = [];
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: {
      writeText: (text: string) => (written.push(text), Promise.resolve()),
    },
  });
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <CommandPalette />
      </MemoryRouter>
    </QueryClientProvider>
  );
  act(() => openCommandPalette());
  return { user, written };
}

/** Waits until the workflow's runs have arrived and been rendered. */
async function runsLoaded() {
  await waitFor(() => expect(served).toBe(1));
  await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
}

describe('CommandPalette on a run view', () => {
  it('copies the commands that run this workflow', async () => {
    const { user, written } = open('/sessions/dogfood/workflow/check');
    await user.click(await screen.findByTestId('palette-here:cli'));
    expect(written).toEqual([
      `yukta-cli workflow start dogfood check --url ${window.location.origin}`,
    ]);
    act(() => openCommandPalette());
    await user.click(await screen.findByTestId('palette-here:mcp'));
    expect(JSON.parse(written[1])).toEqual({
      name: 'start_workflow',
      arguments: { sessionId: 'dogfood', workflowId: 'check' },
    });
  });

  it('stops the run in the URL only on a second press', async () => {
    const { user } = open('/sessions/dogfood/workflow/check?exec=e-live-0002');
    const stop = await screen.findByTestId('palette-here:stop');
    expect(stop).toHaveTextContent('Stop run e-live-0');
    await user.click(stop);
    expect(screen.getByTestId('palette-here:stop')).toHaveTextContent(
      'Press again to stop run e-live-0'
    );
    expect(stopped).toEqual([]);
    await user.click(screen.getByTestId('palette-here:stop'));
    await waitFor(() => expect(stopped).toEqual(['e-live-0002']));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('offers no stop for a finished run', async () => {
    open('/sessions/dogfood/workflow/check');
    expect(await screen.findByTestId('palette-here:cli')).toBeInTheDocument();
    await runsLoaded();
    // the newest run (no ?exec=) has finished
    expect(screen.queryByTestId('palette-here:stop')).not.toBeInTheDocument();
  });

  it('offers no stop before the first run', async () => {
    runs = [];
    open('/sessions/dogfood/workflow/check');
    expect(await screen.findByTestId('palette-here:cli')).toBeInTheDocument();
    await runsLoaded();
    expect(screen.queryByTestId('palette-here:stop')).not.toBeInTheDocument();
  });

  it('hides Stop from a key that may not control runs', async () => {
    setMe({ permissions: { controlRuns: false } } as unknown as Me);
    open('/sessions/dogfood/workflow/check?exec=e-live-0002');
    expect(await screen.findByTestId('palette-here:cli')).toBeInTheDocument();
    await runsLoaded();
    expect(screen.queryByTestId('palette-here:stop')).not.toBeInTheDocument();
  });

  it('has no workflow actions elsewhere', async () => {
    open('/runs');
    expect(await screen.findByTestId('palette-page:/runs')).toBeInTheDocument();
    expect(screen.queryByTestId('palette-here:cli')).not.toBeInTheDocument();
  });
});
