// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
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
    displayName: 'Local program',
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

function PathProbe() {
  const { pathname } = useLocation();
  return <output aria-label="path">{pathname}</output>;
}

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

describe('the Decision makers view', () => {
  const nav = () => screen.getByRole('navigation', { name: 'Decision makers' });

  it('lists the decision makers by where they decide, with a tooltip for how one is used', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?view=decision-makers');
    expect(
      await screen.findByRole('heading', { name: 'Rules', level: 2 })
    ).toBeInTheDocument();
    const names = within(nav())
      .getAllByRole('button')
      .map((b) => b.textContent);
    // Rules, External system and On this server in order, the unknown category last
    expect(names).toHaveLength(4);
    expect(names[0]).toContain('Rules');
    expect(names[1]).toContain('External system');
    expect(names[2]).toContain('Local program');
    expect(names[3]).toContain('Odd one');
    expect(screen.getByText('What rules does')).toBeInTheDocument();
    await user.hover(screen.getByRole('img', { name: 'How it is used' }));
    expect(await screen.findByText('Use it for exact cases')).toHaveAttribute(
      'data-slot',
      'tooltip-content'
    );
    expect(screen.queryByText('Acts on its own')).toBeNull();
  });

  it('shows the signals as tiles, the settings, presets, the example and who uses it', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?view=decision-makers&type=http');
    expect(
      await screen.findByRole('heading', { name: 'External system', level: 2 })
    ).toBeInTheDocument();
    expect(screen.getByText('Acts on its own')).toBeInTheDocument();
    const signals = screen.getByRole('region', { name: 'Signals' });
    expect(within(signals).getByText('Events')).toBeInTheDocument();
    expect(within(signals).getByText('Reviews')).toBeInTheDocument();
    // a signal the UI does not know keeps its own name
    await user.hover(
      within(signals).getByText('ODD').parentElement as HTMLElement
    );
    expect(await screen.findAllByText('ODD')).not.toHaveLength(0);
    await user.hover(
      within(signals).getByText('Reviews').parentElement as HTMLElement
    );
    expect(
      await screen.findByText(
        'Reviews: A command someone sent, for it to allow or refuse'
      )
    ).toHaveAttribute('data-slot', 'tooltip-content');
    const settings = screen.getByRole('region', { name: 'Settings' });
    expect(within(settings).getByText('(required)')).toBeInTheDocument();
    expect(within(settings).getByText('3000')).toBeInTheDocument();
    expect(
      await within(screen.getByRole('region', { name: 'Presets' })).findByText(
        'No presets'
      )
    ).toBeInTheDocument();
    expect(screen.getByText('Example')).toBeInTheDocument();
    expect(screen.getByText(/https:\/\/x/)).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'agent' })).toHaveAttribute(
      'href',
      '/supervisors?supervisor=agent'
    );
  });

  it('shows presets, a type with no settings, and nobody using it; filters and chooses', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?view=decision-makers');
    await screen.findByRole('heading', { name: 'Rules', level: 2 });
    expect(
      screen.getByText(/It has no settings of its own/)
    ).toBeInTheDocument();
    expect(
      await screen.findByText('Pauses after failures')
    ).toBeInTheDocument();
    expect(
      await screen.findByText('No supervisor is wired to it')
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Odd one/ }));
    expect(screen.getByLabelText('where')).toHaveTextContent('type=odd');
    // a category the UI does not know shows as it is
    expect(await screen.findByText('GUARD')).toBeInTheDocument();
    await user.type(
      screen.getByRole('searchbox', { name: 'Filter decision makers' }),
      'prog'
    );
    expect(
      screen.queryByRole('button', { name: /Rules/ })
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /Local program/ })
    ).toBeInTheDocument();
  });

  it('filters by category, says when nothing matches, and clears the filters', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?view=decision-makers');
    await screen.findByRole('heading', { name: 'Rules', level: 2 });
    const count = () => within(nav()).getAllByRole('button').length;
    await user.click(screen.getByLabelText('Filter by category'));
    await user.click(await screen.findByRole('option', { name: 'Other' }));
    await user.keyboard('{Escape}');
    expect(count()).toBe(1);
    await user.type(
      screen.getByRole('searchbox', { name: 'Filter decision makers' }),
      'zzz'
    );
    expect(screen.getByText('No decision maker matches')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(count()).toBe(4);
  });

  it('folds the list on a phone once a decision maker is chosen', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?view=decision-makers&type=http');
    await screen.findByRole('heading', { name: 'External system', level: 2 });
    expect(nav()).toHaveClass('max-md:hidden');
    await user.click(
      screen.getByRole('button', { name: /All decision makers \(4\)/ })
    );
    expect(nav()).not.toHaveClass('max-md:hidden');
    await user.click(within(nav()).getByRole('button', { name: /Rules/ }));
    expect(nav()).toHaveClass('max-md:hidden');
    expect(
      await screen.findByRole('heading', { name: 'Rules', level: 2 })
    ).toHaveFocus();
  });

  it('goes from the old decision-makers link to the view, keeping the type', async () => {
    renderAt('/supervisors?tab=decision-makers&type=http');
    expect(
      await screen.findByRole('heading', { name: 'External system', level: 2 })
    ).toBeInTheDocument();
    expect(screen.getByLabelText('where')).toHaveTextContent(
      'view=decision-makers'
    );
    expect(screen.getByLabelText('where')).not.toHaveTextContent('tab=');
  });

  it('says when the catalog, a decision maker, its presets or its users cannot be read', async () => {
    server.use(
      http.get('/api/supervisor-engines/:type/usage', () => failed()),
      http.get('/api/supervisor-engines/:type/presets', () => failed())
    );
    renderAt('/supervisors?view=decision-makers');
    await screen.findByRole('heading', { name: 'Rules', level: 2 });
    expect(
      await screen.findByText('Failed to load the presets.')
    ).toBeInTheDocument();
    expect(
      await screen.findByText('Failed to load who uses it.')
    ).toBeInTheDocument();
  });

  it('says when one decision maker cannot be read', async () => {
    server.use(http.get('/api/supervisor-engines/:type', () => failed()));
    renderAt('/supervisors?view=decision-makers');
    expect(
      await screen.findByText('Failed to load rules.')
    ).toBeInTheDocument();
  });

  it('shows an object default as JSON in the settings', async () => {
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
    renderAt('/supervisors?view=decision-makers&type=http');
    await screen.findByRole('heading', { name: 'External system', level: 2 });
    expect(
      within(screen.getByRole('region', { name: 'Settings' })).getByText(
        '{"A":"b"}'
      )
    ).toBeInTheDocument();
  });

  it('says when there are none, or the catalog fails', async () => {
    server.use(http.get('/api/supervisor-engines', () => ok([])));
    const first = renderAt('/supervisors?view=decision-makers');
    expect(
      await screen.findByText('No decision makers are installed')
    ).toBeInTheDocument();
    first.unmount();
    server.use(http.get('/api/supervisor-engines', () => failed()));
    renderAt('/supervisors?view=decision-makers');
    expect(
      await screen.findByText('Failed to load the decision makers.')
    ).toBeInTheDocument();
  });
});

describe('a supervisor’s Settings and History', () => {
  const at = async (tab: 'Settings' | 'Changes') => {
    const user = userEvent.setup();
    // Settings are part of the Decision maker section; Changes opens when named
    renderAt(
      tab === 'Changes' ? '/supervisors?section=changes' : '/supervisors'
    );
    await screen.findByRole('heading', { name: 'lead' });
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

describe('the activity tab', () => {
  it('moves to the control activity, which is where the activity lives', async () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter initialEntries={['/supervisors?tab=activity']}>
          <SupervisorsPage />
          <PathProbe />
        </MemoryRouter>
      </QueryClientProvider>
    );
    await waitFor(() =>
      expect(screen.getByLabelText('path')).toHaveTextContent('/control')
    );
  });
});

describe('closing the decision maker types', () => {
  it('takes the tab out of the address when the sheet is closed', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?tab=decision-makers');
    expect(
      await screen.findByRole('heading', { name: 'Rules', level: 2 })
    ).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() =>
      expect(screen.getByLabelText('where')).not.toHaveTextContent(
        'tab=decision-makers'
      )
    );
  });
});

describe('lineDiff', () => {
  it('says when nothing changed', () => {
    expect(lineDiff('a\nb', 'a\nb')).toBe('No difference');
    expect(lineDiff('a\nb', 'a\nc')).toBe('- b\n+ c');
  });
});
