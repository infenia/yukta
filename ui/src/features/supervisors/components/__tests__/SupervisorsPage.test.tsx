// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { setMe, type Me } from '@/lib/identity';
import { SupervisorsPage } from '../SupervisorsPage';
import { lineDiff } from '../SettingsHistoryTabs';
import { createTestQueryClient } from '@/test/utils/testUtils';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });
const failed = () =>
  HttpResponse.json(
    { timestamp: 't', status: 500, message: 'boom', path: '/' },
    { status: 500 }
  );

const summaries = [
  { type: 'rules', displayName: 'Rules', category: 'RULES', icon: 'rule' },
  {
    type: 'http',
    displayName: 'External system',
    category: 'EXTERNAL_SYSTEM',
    icon: 'api',
  },
  {
    type: 'process',
    displayName: 'Program on this server',
    category: 'ON_THIS_SERVER',
    icon: 'terminal',
  },
  { type: 'odd', displayName: 'Odd one', category: 'GUARD', icon: null },
];
const field = (name: string, extra: object = {}) => ({
  name,
  type: 'STRING',
  required: false,
  defaultValue: null,
  options: [],
  description: `About ${name}`,
  ...extra,
});
const details = (type: string) => ({
  ...summaries.find((s) => s.type === type),
  type,
  description: `What ${type} does`,
  usagePattern: type === 'rules' ? 'Use it for exact cases' : '',
  configSchema:
    type === 'http'
      ? [
          field('url', { required: true }),
          field('timeoutMs', { type: 'NUMBER', defaultValue: 3000 }),
          field('headers', { type: 'MAP' }),
        ]
      : [],
  commonSchema: [],
  defaultAllowedCommands: null,
  exampleSettings: { url: 'https://x' },
  signals: type === 'http' ? ['EVENT', 'REVIEW', 'ODD'] : undefined,
  canInitiate: type === 'http',
});
const sup = (id: string, extra: object = {}) => ({
  id,
  type: 'http',
  description: null,
  settings: { url: 'https://a', headers: { A: 'b' } },
  dryRun: false,
  system: false,
  attachments: 0,
  applied: 0,
  rejected: 0,
  recorded: 0,
  rateLimited: 0,
  parents: [],
  ...extra,
});

let versions: unknown[] = [];
let rolledBack = '';
const server = setupServer(
  http.get('/api/supervisor-engines', () => ok(summaries)),
  http.get('/api/supervisor-engines/:type', ({ params }) =>
    ok(details(String(params.type)))
  ),
  http.get('/api/supervisor-engines/:type/usage', ({ params }) =>
    ok(params.type === 'http' ? ['lead', 'agent'] : [])
  ),
  http.get('/api/supervisor-engines/:type/presets', ({ params }) =>
    ok(
      params.type === 'rules'
        ? [
            {
              name: 'failure-guard',
              description: 'Pauses after failures',
              settings: { rules: [] },
            },
          ]
        : []
    )
  ),
  http.get('/api/supervisors', () => ok([sup('lead')])),
  http.get('/api/supervisors/assignments', () => ok([])),
  http.get('/api/supervisors/:id/assignments', () => ok([])),
  http.get('/api/supervisors/:id/versions', () => ok(versions)),
  http.post('/api/supervisors/:id/rollback', ({ request }) => {
    rolledBack = new URL(request.url).search;
    return ok(sup('lead'));
  }),
  http.get('/api/supervisors/decisions', () => ok([]))
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  versions = [];
  rolledBack = '';
  setMe(null);
});
afterAll(() => server.close());

function Where() {
  const { search } = useLocation();
  return <output aria-label="where">{search}</output>;
}

const renderAt = (url: string) =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <SupervisorsPage />
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('the Decision makers tab', () => {
  it('groups decision makers by where they decide and shows the first', async () => {
    renderAt('/supervisors?tab=decision-makers');
    expect(
      await screen.findByRole('heading', { name: 'Rules', level: 2 })
    ).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Decision makers' });
    expect(
      within(nav).getByRole('heading', { name: /External system/ })
    ).toBeInTheDocument();
    expect(
      within(nav).getByRole('heading', { name: /On this server/ })
    ).toBeInTheDocument();
    expect(
      within(nav).getByRole('heading', { name: /Other/ })
    ).toBeInTheDocument();
    expect(screen.getByText('Use it for exact cases')).toBeInTheDocument();
    expect(screen.getByText('events')).toBeInTheDocument();
  });

  it('shows signals, settings, presets, the example and who uses it', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?tab=decision-makers&type=http');
    expect(
      await screen.findByRole('heading', { name: 'External system', level: 2 })
    ).toBeInTheDocument();
    expect(screen.getByText('can act on its own')).toBeInTheDocument();
    expect(screen.getByText('Events, Reviews, ODD')).toBeInTheDocument();
    expect(
      screen.getByText('A command someone sent, for it to allow or refuse')
    ).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Settings' }));
    expect(screen.getByText('(required)')).toBeInTheDocument();
    expect(screen.getByText('3000')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Presets' }));
    expect(await screen.findByText('No presets')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Example' }));
    expect(screen.getByText(/https:\/\/x/)).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Used by' }));
    expect(await screen.findByRole('link', { name: 'agent' })).toHaveAttribute(
      'href',
      '/supervisors?supervisor=agent'
    );
  });

  it('shows presets, a type with no settings, and nobody using it; filters and chooses', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?tab=decision-makers');
    await screen.findByRole('heading', { name: 'Rules', level: 2 });
    await user.click(screen.getByRole('tab', { name: 'Settings' }));
    expect(
      screen.getByText(/It has no settings of its own/)
    ).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Presets' }));
    expect(
      await screen.findByText('Pauses after failures')
    ).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Used by' }));
    expect(
      await screen.findByText('No supervisor is wired to it')
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Odd one/ }));
    expect(screen.getByLabelText('where')).toHaveTextContent('type=odd');
    expect(await screen.findByText('GUARD')).toBeInTheDocument();
    await user.type(
      screen.getByRole('searchbox', { name: 'Filter decision makers' }),
      'prog'
    );
    expect(
      screen.queryByRole('button', { name: /Rules/ })
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /Program on this server/ })
    ).toBeInTheDocument();
  });

  it('says when the catalog, a decision maker, its presets or its users cannot be read', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/supervisor-engines/:type/usage', () => failed()),
      http.get('/api/supervisor-engines/:type/presets', () => failed())
    );
    renderAt('/supervisors?tab=decision-makers');
    await screen.findByRole('heading', { name: 'Rules', level: 2 });
    await user.click(screen.getByRole('tab', { name: 'Presets' }));
    expect(
      await screen.findByText('Failed to load the presets.')
    ).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Used by' }));
    expect(
      await screen.findByText('Failed to load who uses it.')
    ).toBeInTheDocument();
  });

  it('says when one decision maker cannot be read', async () => {
    server.use(http.get('/api/supervisor-engines/:type', () => failed()));
    renderAt('/supervisors?tab=decision-makers');
    expect(
      await screen.findByText('Failed to load rules.')
    ).toBeInTheDocument();
  });

  it('shows an object default as JSON in the settings', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/supervisor-engines/:type', ({ params }) =>
        ok({
          ...details(String(params.type)),
          configSchema: [
            field('headers', { type: 'MAP', defaultValue: { A: 'b' } }),
          ],
        })
      )
    );
    renderAt('/supervisors?tab=decision-makers&type=http');
    await screen.findByRole('heading', { name: 'External system', level: 2 });
    await user.click(screen.getByRole('tab', { name: 'Settings' }));
    expect(screen.getByText('{"A":"b"}')).toBeInTheDocument();
  });

  it('says when there are none, or the catalog fails', async () => {
    server.use(http.get('/api/supervisor-engines', () => ok([])));
    const first = renderAt('/supervisors?tab=decision-makers');
    expect(
      await screen.findByText('No decision makers are installed')
    ).toBeInTheDocument();
    first.unmount();
    server.use(http.get('/api/supervisor-engines', () => failed()));
    renderAt('/supervisors?tab=decision-makers');
    expect(
      await screen.findByText('Failed to load the decision makers.')
    ).toBeInTheDocument();
  });
});

describe('a supervisor’s Settings and History', () => {
  const at = async (tab: 'Settings' | 'Changes') => {
    const user = userEvent.setup();
    renderAt('/supervisors');
    await screen.findByRole('heading', { name: 'lead' });
    await user.click(screen.getByRole('tab', { name: tab }));
    return user;
  };

  it('shows each setting with its value, default or a dash, and the JSON', async () => {
    await at('Settings');
    expect(await screen.findByText('https://a')).toBeInTheDocument();
    expect(screen.getByText('3000')).toBeInTheDocument();
    expect(screen.getByText('{"A":"b"}')).toBeInTheDocument();
    expect(screen.getByText('All settings as JSON')).toBeInTheDocument();
  });

  it('shows a dash for a setting with neither a value nor a default', async () => {
    server.use(
      http.get('/api/supervisors', () => ok([sup('lead', { settings: {} })]))
    );
    await at('Settings');
    expect(await screen.findByText('All settings as JSON')).toBeInTheDocument();
    const url = screen.getByRole('row', { name: /url/ });
    expect(within(url).getByText('—')).toBeInTheDocument();
    expect(within(url).queryByText('https://a')).not.toBeInTheDocument();
    expect(screen.getByText('3000')).toBeInTheDocument();
  });

  it('still shows the settings as JSON when the decision maker cannot be read', async () => {
    server.use(http.get('/api/supervisor-engines/:type', () => failed()));
    await at('Settings');
    expect(await screen.findByText('All settings as JSON')).toBeInTheDocument();
    expect(screen.queryByText('What it does')).not.toBeInTheDocument();
  });

  it('lists the versions, compares one with the newest and restores it', async () => {
    setMe({ permissions: { editSessions: true } } as unknown as Me);
    versions = [
      {
        version: 2,
        savedAt: '2026-11-09T10:00:00Z',
        supervisor: sup('lead', { settings: { url: 'https://b' } }),
      },
      { version: 1, savedAt: '2026-11-08T10:00:00Z', supervisor: sup('lead') },
    ];
    const user = await at('Changes');
    expect(await screen.findByText('v2')).toBeInTheDocument();
    expect(screen.getByText('current')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Compare' }));
    const diff = screen.getByRole('region', { name: 'v1 to v2' });
    expect(
      within(diff).getByText(/\+ "url": "https:\/\/b"/)
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Restore' }));
    await expect.poll(() => rolledBack).toBe('?version=1');
  });

  it('says when nothing is kept', async () => {
    await at('Changes');
    expect(await screen.findByText('No versions kept')).toBeInTheDocument();
  });

  it('says when the versions cannot be read', async () => {
    server.use(http.get('/api/supervisors/:id/versions', () => failed()));
    await at('Changes');
    expect(
      await screen.findByText('Failed to load the versions.')
    ).toBeInTheDocument();
  });

  it('shows why a restore failed', async () => {
    versions = [
      { version: 2, savedAt: '2026-11-09T10:00:00Z', supervisor: sup('lead') },
      { version: 1, savedAt: '2026-11-08T10:00:00Z', supervisor: sup('lead') },
    ];
    server.use(http.post('/api/supervisors/:id/rollback', () => failed()));
    const user = await at('Changes');
    await user.click(await screen.findByRole('button', { name: 'Restore' }));
    expect(await screen.findByText('boom')).toBeInTheDocument();
  });
});

describe('lineDiff', () => {
  it('says when nothing changed', () => {
    expect(lineDiff('a\nb', 'a\nb')).toBe('No difference');
    expect(lineDiff('a\nb', 'a\nc')).toBe('- b\n+ c');
  });
});
