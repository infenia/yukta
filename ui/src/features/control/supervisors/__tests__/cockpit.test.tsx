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
import { SupervisorCockpit } from '../SupervisorCockpit';
import { buildScope, scopeLabel } from '../../api/supervisors';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const running = (id: string, over: Record<string, unknown> = {}) => ({
  id,
  kind: 'SUPERVISOR',
  type: 'RuleSupervisor',
  order: 0,
  dryRun: false,
  maxCommandsPerSecond: 20,
  applied: 3,
  rejected: 1,
  recorded: 2,
  rateLimited: 0,
  scope: 'GLOBAL',
  scopeSessionId: null,
  scopeWorkflowId: null,
  ...over,
});

let supervisors: unknown[] = [];
let bindings: {
  id: string;
  profileId: string;
  scope: string;
  enabled: boolean;
}[] = [];
let profiles: {
  id: string;
  type: string;
  settings: Record<string, unknown>;
  dryRun: boolean;
}[] = [];
let decisions: unknown[] = [];
let writes: string[] = [];
let writeStatus = 200;

const refuse = () =>
  HttpResponse.json({ message: 'no' }, { status: writeStatus });

const server = setupServer(
  http.get('/api/supervisors/status', () => ok(supervisors)),
  http.get('/api/supervisor-types', () =>
    ok([{ type: 'rule-supervisor' }, { type: 'model-control-plane' }])
  ),
  http.get('/api/supervisors', () => ok(profiles)),
  http.get('/api/supervisors/attachments', () =>
    ok(
      bindings.map(({ profileId, ...b }) => ({
        ...b,
        supervisorId: profileId,
        source: 'MANUAL',
      }))
    )
  ),
  http.get('/api/supervisors/decisions', () => ok(decisions)),
  http.get('/api/sessions/summaries', () =>
    ok([{ sessionId: 's1', name: 'Alpha' }])
  ),
  http.post('/api/supervisors', async ({ request }) => {
    if (writeStatus !== 200) return refuse();
    const body = (await request.json()) as (typeof profiles)[number];
    writes.push(`create ${JSON.stringify(body)}`);
    profiles = [...profiles, body];
    return ok(body);
  }),
  http.put('/api/supervisors/:id', async ({ request }) => {
    if (writeStatus !== 200) return refuse();
    const body = (await request.json()) as (typeof profiles)[number];
    writes.push(`replace ${JSON.stringify(body)}`);
    profiles = profiles.map((p) => (p.id === body.id ? body : p));
    return ok(body);
  }),
  http.delete('/api/supervisors/:id', ({ params }) => {
    writes.push(`delete profile ${String(params.id)}`);
    profiles = profiles.filter((p) => p.id !== params.id);
    return ok(null);
  }),
  http.post('/api/supervisors/attachments', async ({ request }) => {
    if (writeStatus !== 200) return refuse();
    const body = (await request.json()) as {
      id?: string;
      supervisorId: string;
      scope: string;
      enabled: boolean;
    };
    writes.push(`attach ${JSON.stringify(body)}`);
    const { supervisorId, ...rest } = body;
    const binding = {
      ...rest,
      profileId: supervisorId,
      id: body.id ?? 'new-binding',
    };
    bindings = [...bindings.filter((b) => b.id !== binding.id), binding];
    return ok(binding);
  }),
  http.delete('/api/supervisors/attachments/:id', ({ params }) => {
    writes.push(`detach ${String(params.id)}`);
    bindings = bindings.filter((b) => b.id !== params.id);
    return ok(null);
  })
);

beforeAll(() => server.listen());
beforeEach(() => installMockEventSource());
afterEach(() => {
  server.resetHandlers();
  supervisors = [];
  bindings = [];
  profiles = [];
  decisions = [];
  writes = [];
  writeStatus = 200;
});
afterAll(() => server.close());

describe('the supervisor cockpit', () => {
  it('has an empty state with the two ways in', async () => {
    renderWithProviders(<SupervisorCockpit />);

    expect(await screen.findByText('No supervisors')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'New supervisor' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Attach supervisor' })
    ).toBeInTheDocument();
    expect(await screen.findByText('No profile yet')).toBeInTheDocument();
    expect(
      await screen.findByText('No supervisor has decided anything yet')
    ).toBeInTheDocument();
  });

  it('shows a card for an attached supervisor and one configured at startup', async () => {
    profiles = [
      { id: 'p', type: 'rule-supervisor', settings: {}, dryRun: false },
    ];
    bindings = [
      { id: 'b1', profileId: 'p', scope: 'session:s1', enabled: true },
    ];
    supervisors = [
      running('b1', { scope: 'SESSION', scopeSessionId: 's1' }),
      running('startup-rules'),
    ];
    renderWithProviders(<SupervisorCockpit />);

    const card = await screen.findByRole('article', { name: 'Supervisor b1' });
    expect(within(card).getByText('Session s1')).toBeInTheDocument();
    expect(within(card).getByRole('switch', { name: 'Enabled' })).toBeChecked();
    const startup = screen.getByRole('article', {
      name: 'Supervisor startup-rules',
    });
    expect(within(startup).getByText('Global')).toBeInTheDocument();
    expect(
      within(startup).getByText('Configured at startup.')
    ).toBeInTheDocument();
    expect(
      within(startup).queryByRole('button', { name: 'Detach' })
    ).toBeNull();
  });

  it('shows a disabled binding as not running', async () => {
    profiles = [
      { id: 'p', type: 'rule-supervisor', settings: {}, dryRun: false },
    ];
    bindings = [{ id: 'off', profileId: 'p', scope: 'global', enabled: false }];
    renderWithProviders(<SupervisorCockpit />);

    const card = await screen.findByRole('article', { name: 'Supervisor off' });
    expect(within(card).getByText('Disabled')).toBeInTheDocument();
    expect(within(card).getByText('Not running.')).toBeInTheDocument();
  });

  it('toggles dry run through the profile, enable through the binding, and detaches after a confirmation', async () => {
    profiles = [
      {
        id: 'p',
        type: 'rule-supervisor',
        settings: { apiKey: '***' },
        dryRun: false,
      },
    ];
    bindings = [{ id: 'b1', profileId: 'p', scope: 'global', enabled: true }];
    supervisors = [running('b1')];
    renderWithProviders(<SupervisorCockpit />);
    const user = userEvent.setup();
    const card = await screen.findByRole('article', { name: 'Supervisor b1' });

    await user.click(within(card).getByRole('switch', { name: 'Dry run' }));
    await waitFor(() =>
      expect(
        writes.some(
          (w) =>
            w.startsWith('replace') &&
            w.includes('"dryRun":true') &&
            w.includes('"apiKey":"***"')
        )
      ).toBe(true)
    );

    await user.click(within(card).getByRole('switch', { name: 'Enabled' }));
    await waitFor(() =>
      expect(
        writes.some(
          (w) =>
            w.startsWith('attach') &&
            w.includes('"enabled":false') &&
            w.includes('"id":"b1"')
        )
      ).toBe(true)
    );

    await user.click(within(card).getByRole('button', { name: 'Detach' }));
    expect(writes.some((w) => w.startsWith('detach'))).toBe(false);
    await user.click(
      within(card).getByRole('button', { name: 'Confirm detach' })
    );
    await waitFor(() => expect(writes).toContain('detach b1'));
  });

  it('says an ADMIN key is needed when a change is refused', async () => {
    profiles = [
      { id: 'p', type: 'rule-supervisor', settings: {}, dryRun: false },
    ];
    bindings = [{ id: 'b1', profileId: 'p', scope: 'global', enabled: true }];
    supervisors = [running('b1')];
    writeStatus = 403;
    renderWithProviders(<SupervisorCockpit />);
    const card = await screen.findByRole('article', { name: 'Supervisor b1' });

    await userEvent
      .setup()
      .click(within(card).getByRole('switch', { name: 'Enabled' }));

    expect(
      await within(card).findByText('This needs an ADMIN key.')
    ).toBeInTheDocument();
  });

  it('lists the recent decisions with their outcome', async () => {
    decisions = [
      {
        time: new Date().toISOString(),
        supervisorId: 'b1',
        outcome: 'rejected',
        command: 'PauseWorkflowCommand',
        executionId: 'run-9',
        reason: 'no',
      },
    ];
    renderWithProviders(<SupervisorCockpit />);

    const row = (await screen.findByText('rejected')).closest('li');
    expect(row).toHaveTextContent('PauseWorkflowCommand');
    expect(row).toHaveTextContent('run-9');
    expect(row).toHaveTextContent('(no)');
  });

  it('shows the newer outcomes, and an unknown one in a neutral colour', async () => {
    const decision = (outcome: string) => ({
      time: new Date().toISOString(),
      supervisorId: 'b1',
      outcome,
      command: `Cmd-${outcome}`,
      executionId: 'run-9',
      reason: null,
    });
    decisions = [
      decision('arbitrated'),
      decision('awaiting-approval'),
      decision('something-new'),
    ];
    renderWithProviders(<SupervisorCockpit />);

    expect(await screen.findByText('arbitrated')).toHaveClass('text-attention');
    expect(screen.getByText('awaiting-approval')).toHaveClass('text-primary');
    expect(screen.getByText('something-new')).toHaveClass(
      'text-on-surface-variant'
    );
  });

  it('links to the wizard to create a supervisor and to edit a profile, and deletes one', async () => {
    profiles = [
      {
        id: 'p',
        type: 'model-control-plane',
        settings: { apiKey: '***' },
        dryRun: false,
      },
    ];
    renderWithProviders(<SupervisorCockpit />);
    const user = userEvent.setup();

    expect(
      await screen.findByRole('link', { name: 'New supervisor' })
    ).toHaveAttribute('href', '/supervisors/new');
    expect(
      await screen.findByRole('link', { name: 'Edit profile p' })
    ).toHaveAttribute('href', '/supervisors/p/edit');
    await user.click(
      await screen.findByRole('button', { name: 'Delete profile p' })
    );
    await waitFor(() => expect(writes).toContain('delete profile p'));
  });

  it('attaches a profile to a session', async () => {
    profiles = [
      { id: 'p', type: 'rule-supervisor', settings: {}, dryRun: false },
    ];
    renderWithProviders(<SupervisorCockpit />);
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole('button', { name: 'Attach supervisor' })
    );
    const dialog = await screen.findByRole('dialog', {
      name: 'Attach supervisor',
    });

    await user.selectOptions(
      within(dialog).getByLabelText('Applies to'),
      'session'
    );
    const attach = within(dialog).getByRole('button', { name: 'Attach' });
    expect(attach).toBeDisabled(); // no session chosen
    await screen.findByRole('option', { name: 'Alpha' });
    await user.selectOptions(within(dialog).getByLabelText('Session'), 's1');
    await user.click(attach);

    await waitFor(() => expect(writes[0]).toContain('"scope":"session:s1"'));
    expect(writes[0]).toContain('"supervisorId":"p"');
    expect(writes[0]).toContain('"enabled":true');
  });
});

describe('the scope helpers', () => {
  it('labels a section with its workflow and nodes', () => {
    expect(scopeLabel('workflow:s/w//build.*,deploy')).toBe(
      'Section s/w: build.*, deploy'
    );
    expect(scopeLabel('workflow:s/w')).toBe('Workflow s/w');
  });

  it('builds a scope and refuses an incomplete one', () => {
    expect(buildScope('global', '', '')).toBe('global');
    expect(buildScope('session', 's', '')).toBe('session:s');
    expect(buildScope('session', '', '')).toBeNull();
    expect(buildScope('workflow', '', 'w')).toBe('workflow:w');
    expect(buildScope('workflow', 's', 'w')).toBe('workflow:s/w');
    expect(buildScope('workflow', 's', '')).toBeNull();
    expect(buildScope('section', 's', 'w', ' build.* ,deploy ')).toBe(
      'workflow:s/w//build.*,deploy'
    );
    expect(buildScope('section', '', 'w', 'a b')).toBe('workflow:w//a,b');
    expect(buildScope('section', 's', 'w', ' , ')).toBeNull();
    expect(buildScope('section', 's', 'w')).toBeNull();
  });
});
