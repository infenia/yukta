// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  beforeEach,
  afterAll,
  afterEach,
} from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { installMockEventSource } from '@/test/mocks/MockEventSource';
import { ControlPage } from '../ControlPage';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const beat = (workflowId: string, nodeId: string, executionId: string) => ({
  sessionId: 's1',
  workflowId,
  nodeId,
  executionId,
  timestamp: new Date(Date.now() - 3000).toISOString(),
});

let beats: ReturnType<typeof beat>[] = [];
let beatsFail = false;
let commandStatus = 200;
let commandBody: unknown;
let commandQuery = '';
let commandReply: unknown = { payload: { done: true } };

const server = setupServer(
  http.get('/api/supervisors', () => ok([])),
  http.get('/api/supervisors/profiles', () => ok([])),
  http.get('/api/supervisors/bindings', () => ok([])),
  http.get('/api/supervisors/decisions', () => ok([])),
  http.get('/api/supervisors/types', () => ok([])),
  http.get('/api/sessions/summaries', () => ok([])),
  http.get('/api/metrics/snapshot', () => ok({ time: 0, values: {} })),
  http.get('/api/metrics/series', () => ok({ range: '1h', series: {} })),
  http.get('/api/metrics/heatmap', () => ok([])),
  http.get('/api/metrics/modules', () => ok([])),
  http.get('/api/metrics/annotations', () => ok([])),
  http.get('/api/metrics/runs/live', () => ok([])),
  http.get('/api/control/audit', () => ok([])),
  http.get('/api/workers', () => ok([])),
  http.get('/api/scheduler/limits', () =>
    ok({
      maxConcurrent: 0,
      maxPerWorkflow: 0,
      running: 0,
      queued: 0,
      queueCapacity: 100,
    })
  ),
  http.get('/api/edition', () =>
    ok({ edition: 'PLUS', license: 'x', features: [], source: null })
  ),
  http.get('/api/control/heartbeats', () =>
    beatsFail ? HttpResponse.json({ message: 'x' }, { status: 500 }) : ok(beats)
  ),
  http.post(
    '/api/control/workflows/:wf/nodes/:node/command',
    async ({ request }) => {
      commandBody = await request.json();
      commandQuery = new URL(request.url).search;
      return commandStatus === 200
        ? ok(commandReply)
        : HttpResponse.json({ message: 'denied' }, { status: commandStatus });
    }
  )
);

beforeAll(() => server.listen());
beforeEach(() => installMockEventSource());
afterEach(() => {
  beats = [];
  beatsFail = false;
  commandStatus = 200;
  commandBody = undefined;
  commandReply = { payload: { done: true } };
});
afterAll(() => server.close());

async function pick(user: ReturnType<typeof userEvent.setup>) {
  await user.click(
    await screen.findByRole('button', { name: 'Send command to n:1' })
  );
}

describe('ControlPage', () => {
  it('shows the empty state when no node is registered', async () => {
    renderWithProviders(<ControlPage />);
    expect(await screen.findByText('No active nodes.')).toBeInTheDocument();
  });

  it('reports a failure to load nodes and retries', async () => {
    beatsFail = true;
    renderWithProviders(<ControlPage />);
    // the dashboard's bus panel and the nodes table both read the same heartbeats
    await waitFor(() =>
      expect(
        screen
          .getAllByRole('alert')
          .map((a) => a.textContent)
          .join(' ')
      ).toContain('Failed to load nodes')
    );

    beatsFail = false;
    beats = [beat('a', 'n1', 'run-1')];
    await userEvent
      .setup()
      .click(screen.getAllByRole('button', { name: 'Retry' })[0]);
    expect(await screen.findByText('n1')).toBeInTheDocument();
  });

  it('retries from the nodes table too', async () => {
    beatsFail = true;
    renderWithProviders(<ControlPage />);
    await waitFor(() =>
      expect(
        screen.getAllByRole('button', { name: 'Retry' }).length
      ).toBeGreaterThan(1)
    );

    beatsFail = false;
    beats = [beat('a', 'n1', 'run-1')];
    const retries = screen.getAllByRole('button', { name: 'Retry' });
    await userEvent.setup().click(retries[retries.length - 1]);
    expect(await screen.findByText('n1')).toBeInTheDocument();
  });

  it('lists every node of every run with its heartbeat age, in a fixed order', async () => {
    beats = [
      beat('b', 'other', ''),
      beat('a', 'n:1', 'run-2-0123456789'),
      beat('a', 'n:1', 'run-1-0123456789'),
      beat('a', 'm', 'x'),
    ];
    renderWithProviders(<ControlPage />);
    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(
      rows.map((row) =>
        within(row)
          .getAllByRole('cell')
          .slice(0, 3)
          .map((cell) => cell.textContent)
      )
    ).toEqual([
      ['m', 'a', 'x'],
      ['n:1', 'a', 'run-1-01'],
      ['n:1', 'a', 'run-2-01'],
      ['other', 'b', '—'],
    ]);
    expect(within(rows[1]).getByText(/^\d+ seconds ago$/)).toBeInTheDocument();
    expect(within(rows[1]).getByText('run-1-01')).toHaveAttribute(
      'title',
      'run-1-0123456789'
    );
    expect(
      screen.getByRole('heading', { name: 'Live nodes 4' })
    ).toBeInTheDocument();
  });

  it('sends a command and shows the response with a link to the audit log', async () => {
    beats = [beat('a', 'n:1', 'run-1')];
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await pick(user);
    expect(screen.getByRole('dialog')).toHaveTextContent(
      'recorded in the audit log'
    );

    const editor = screen.getByLabelText('Command (JSON object)');
    await user.clear(editor);
    await user.click(editor);
    await user.paste('{"action":"flush"}');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(await screen.findByText(/"done": true/)).toBeInTheDocument();
    expect(commandBody).toEqual({ action: 'flush' });
    expect(commandQuery).toBe('?sessionId=s1&executionId=run-1');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Find it in the audit log' })
    ).toHaveAttribute('href', '#audit-heading');
  });

  it('shows null when the node answers with no payload', async () => {
    beats = [beat('a', 'n:1', 'run-1')];
    commandReply = {};
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await pick(user);
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(await screen.findByText('null')).toBeInTheDocument();
  });

  it('refuses a command that is not valid before sending it', async () => {
    beats = [beat('a', 'n:1', 'run-1')];
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await pick(user);
    const editor = screen.getByLabelText('Command (JSON object)');
    const submit = async (text: string) => {
      await user.clear(editor);
      await user.click(editor);
      await user.paste(text);
      await user.click(screen.getByRole('button', { name: 'Send' }));
    };

    await submit('{nope');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'not valid JSON'
    );
    await submit('[1]');
    expect(screen.getByRole('alert')).toHaveTextContent(
      'must be a JSON object'
    );
    await submit('{}');
    expect(screen.getByRole('alert')).toHaveTextContent('must not be empty');
    await submit(
      JSON.stringify(
        Object.fromEntries(Array.from({ length: 101 }, (_, i) => [`k${i}`, 1]))
      )
    );
    expect(screen.getByRole('alert')).toHaveTextContent('101 keys');
    expect(commandBody).toBeUndefined();
  });

  it('says an ADMIN key is needed on a 403, and shows other failures', async () => {
    beats = [beat('a', 'n:1', 'run-1')];
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    await pick(user);
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
