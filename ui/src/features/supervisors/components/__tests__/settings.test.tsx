// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  afterAll,
  afterEach,
  beforeEach,
} from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { SettingsTab } from '../SettingsHistoryTabs';
import type { SupervisorView } from '../../types';

const envelope = (data: unknown, status = 200) =>
  HttpResponse.json(
    {
      timestamp: 't',
      status,
      message: status < 400 ? 'ok' : 'nope',
      data,
      path: '/',
    },
    { status }
  );

const base: SupervisorView = {
  id: 'release-lead',
  type: 'http',
  description: null,
  settings: { url: 'https://agent.example.com', secretHeaders: '***' },
  dryRun: false,
  system: false,
  attachments: 0,
  applied: 0,
  rejected: 0,
  recorded: 0,
  rateLimited: 0,
};
const guard: SupervisorView = {
  ...base,
  id: 'release-guard',
  type: 'rules',
  settings: {},
};

const SECRET = 'yag_9f3c-the-secret-e41a';
const metadata = {
  issuedAt: '2026-11-08T10:14:00Z',
  expiresAt: null,
  addresses: ['10.0.4.0/24'],
  lastUsedAt: '2026-11-09T10:00:00Z',
  lastUsedFrom: '10.0.4.7',
};

let rules: unknown;
let rulesStatus = 200;
let runs: unknown[];
let replay: { data: unknown; status: number };
let credential: { data: unknown; status: number };
let issued: string[];
let revoked: number;

const server = setupServer(
  http.get('/api/supervisor-engines/:type', () =>
    envelope({
      type: 'http',
      configSchema: [
        {
          name: 'url',
          type: 'STRING',
          required: false,
          defaultValue: null,
          options: [],
          description: 'Where each signal is posted',
        },
      ],
    })
  ),
  http.get('/api/supervisors/:id/engine/rules', () =>
    envelope(rules, rulesStatus)
  ),
  http.get('/api/executions', () => envelope(runs)),
  http.post('/api/supervisors/:id/engine/rules/replay', () =>
    envelope(replay.data, replay.status)
  ),
  http.get('/api/supervisors/:id/credential', () =>
    envelope(credential.data, credential.status)
  ),
  http.post('/api/supervisors/:id/credential', ({ request }) => {
    issued.push(new URL(request.url).search);
    credential = { data: metadata, status: 200 };
    return envelope({ token: SECRET, expiresAt: null }, 201);
  }),
  http.delete('/api/supervisors/:id/credential', () => {
    revoked++;
    credential = { data: null, status: 404 };
    return envelope(null);
  })
);
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  rules = {
    version: 7,
    rules: {
      schemaVersion: 1,
      rules: [
        {
          id: 'no-stop',
          deny: { command: 'StopWorkflow', reason: 'a deploy is in progress' },
        },
      ],
    },
  };
  rulesStatus = 200;
  runs = [
    {
      sessionId: 's',
      executionId: 'e1',
      workflowId: 'release-flow',
      status: 'FAILED',
      startTime: '2026-11-09T12:00:00Z',
      endTime: null,
    },
    {
      sessionId: 's',
      executionId: 'e2',
      workflowId: 'other',
      status: 'SUCCESS',
      startTime: null,
      endTime: null,
    },
  ];
  replay = { data: [], status: 200 };
  credential = { data: metadata, status: 200 };
  issued = [];
  revoked = 0;
  localStorage.clear();
  sessionStorage.clear();
});

let client: QueryClient;
const show = (supervisor: SupervisorView) => {
  client = createTestQueryClient();
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <SettingsTab supervisor={supervisor} />
      </MemoryRouter>
    </QueryClientProvider>
  );
};

describe('the Settings tab of a rules supervisor', () => {
  it('reads the rules as sentences and shows the document', async () => {
    show(guard);
    expect(await screen.findByText('Rules 1 · version 7')).toBeInTheDocument();
    expect(screen.getByText('someone sends StopWorkflow')).toBeInTheDocument();
    expect(
      screen.getByText('refuse it · reason “a deploy is in progress”')
    ).toBeInTheDocument();
    expect(screen.getByText('The document as JSON')).toBeInTheDocument();
  });

  it('replays the rules on a chosen run', async () => {
    const user = userEvent.setup();
    replay = {
      status: 200,
      data: [
        {
          command: 'PauseWorkflow',
          executionId: 'e2',
          nodeId: null,
          cause: 'event',
          reason: 'too many',
          allowed: true,
        },
        {
          command: 'StopNode',
          executionId: 'e2',
          nodeId: 'deploy',
          cause: 'event',
          reason: null,
          allowed: false,
        },
      ],
    };
    show(guard);
    const run = await screen.findByRole('combobox', { name: 'Run' });
    await waitFor(() => expect(run).toBeEnabled());
    expect(
      screen.getByRole('option', { name: 'other · SUCCESS' })
    ).toBeInTheDocument();
    await user.selectOptions(run, 'e2');
    await user.click(screen.getByRole('button', { name: /Replay on a run/ }));
    const table = await screen.findByRole('table');
    expect(within(table).getByText('the run')).toBeInTheDocument();
    expect(within(table).getByText('deploy')).toBeInTheDocument();
    expect(within(table).getByText('yes')).toBeInTheDocument();
    expect(within(table).getByText('no: refused')).toBeInTheDocument();
  });

  it('says when nothing would have happened, and when the replay fails', async () => {
    const user = userEvent.setup();
    show(guard);
    const button = await screen.findByRole('button', {
      name: /Replay on a run/,
    });
    await waitFor(() => expect(button).toBeEnabled());
    await user.click(button);
    expect(
      await screen.findByText('Nothing would have happened')
    ).toBeInTheDocument();
    replay = { data: null, status: 400 };
    await user.click(button);
    expect(await screen.findByText('nope')).toBeInTheDocument();
  });

  it('cannot replay without runs, and shows a supervisor without rules', async () => {
    runs = [];
    rules = { version: 0, rules: {} };
    show(guard);
    expect(await screen.findByText('No rules')).toBeInTheDocument();
    expect(
      await screen.findByRole('option', { name: 'No runs yet' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /Replay on a run/ })
    ).toBeDisabled();
  });

  it('says when the rules cannot be loaded', async () => {
    rulesStatus = 500;
    rules = null;
    show(guard);
    expect(
      await screen.findByText('Failed to load the rules.')
    ).toBeInTheDocument();
  });
});

describe('the Settings tab of an external system', () => {
  it('shows how it connects, its settings and its credential', async () => {
    show(base);
    expect(
      await screen.findByText('Yukta posts each signal to the url below')
    ).toBeInTheDocument();
    expect(
      await screen.findByText('Where each signal is posted')
    ).toBeInTheDocument();
    const panel = await screen.findByRole('region', { name: 'Credential' });
    expect(await within(panel).findByText('Active')).toBeInTheDocument();
    expect(within(panel).getByText('never')).toBeInTheDocument();
    expect(within(panel).getByText('10.0.4.0/24')).toBeInTheDocument();
    expect(
      within(panel).getByText(/from 10\.0\.4\.7 \(since the server started\)/)
    ).toBeInTheDocument();
  });

  it('says when nothing is posted and the credential is expired or from before the start', async () => {
    credential = {
      status: 200,
      data: {
        issuedAt: null,
        expiresAt: '2020-01-01T00:00:00Z',
        addresses: [],
        lastUsedAt: null,
        lastUsedFrom: null,
      },
    };
    show({ ...base, settings: {} });
    expect(
      await screen.findByText(/no url: nothing is posted/)
    ).toBeInTheDocument();
    const panel = await screen.findByRole('region', { name: 'Credential' });
    expect(await within(panel).findByText('Expired')).toBeInTheDocument();
    expect(
      within(panel).getByText('before the server started')
    ).toBeInTheDocument();
    expect(within(panel).getByText('any')).toBeInTheDocument();
    expect(within(panel).getByText(/not used/)).toBeInTheDocument();
  });

  it('shows a used credential without its address', async () => {
    credential = { status: 200, data: { ...metadata, lastUsedFrom: null } };
    show(base);
    const panel = await screen.findByRole('region', { name: 'Credential' });
    expect(
      await within(panel).findByText(/\(since the server started\)/)
    ).not.toHaveTextContent('from');
  });

  it('shows a new secret once: gone from the page, storage and cache after Done', async () => {
    const user = userEvent.setup();
    credential = { data: null, status: 404 };
    show(base);
    const panel = await screen.findByRole('region', { name: 'Credential' });
    expect(await within(panel).findByText('None')).toBeInTheDocument();
    await user.type(within(panel).getByLabelText(/Works for/), '24');
    await user.type(
      within(panel).getByLabelText(/Allowed addresses/),
      '10.0.4.0/24, 10.0.5.1'
    );
    await user.click(within(panel).getByRole('button', { name: /Create/ }));
    expect(await screen.findByText(SECRET)).toBeInTheDocument();
    expect(issued).toEqual([
      '?ttlHours=24&allowedAddresses=10.0.4.0%2F24&allowedAddresses=10.0.5.1',
    ]);
    expect(
      screen.getByRole('button', { name: 'Copy the credential' })
    ).toBeInTheDocument();
    expect(await within(panel).findByText('Active')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(document.body.innerHTML).not.toContain(SECRET);
    const stored = JSON.stringify({ ...localStorage, ...sessionStorage });
    expect(stored).not.toContain(SECRET);
    const cached = JSON.stringify([
      client
        .getQueryCache()
        .getAll()
        .map((q) => q.state.data),
      client
        .getMutationCache()
        .getAll()
        .map((m) => m.state.data),
    ]);
    expect(cached).not.toContain(SECRET);
  });

  it('rotates without limits, and revokes only after asking', async () => {
    const user = userEvent.setup();
    show(base);
    const panel = await screen.findByRole('region', { name: 'Credential' });
    await user.click(
      await within(panel).findByRole('button', { name: /Rotate/ })
    );
    expect(await screen.findByText(SECRET)).toBeInTheDocument();
    expect(issued).toEqual(['']);

    await user.click(within(panel).getByRole('button', { name: /Revoke/ }));
    const ask = screen.getByRole('alertdialog', {
      name: 'Revoke the credential?',
    });
    await user.click(within(ask).getByRole('button', { name: 'Cancel' }));
    expect(revoked).toBe(0);
    await user.click(within(panel).getByRole('button', { name: /Revoke/ }));
    await user.click(screen.getByRole('button', { name: 'Revoke now' }));
    await waitFor(() => expect(revoked).toBe(1));
    expect(await within(panel).findByText('None')).toBeInTheDocument();
  });

  it('says when an action or the load fails', async () => {
    const user = userEvent.setup();
    show(base);
    const panel = await screen.findByRole('region', { name: 'Credential' });
    server.use(
      http.post('/api/supervisors/:id/credential', () => envelope(null, 409))
    );
    await user.click(
      await within(panel).findByRole('button', { name: /Rotate/ })
    );
    expect(await within(panel).findByText('nope')).toBeInTheDocument();
  });

  it('says when the credential cannot be loaded', async () => {
    credential = { data: null, status: 500 };
    show(base);
    expect(
      await screen.findByText('Failed to load the credential.')
    ).toBeInTheDocument();
  });
});
