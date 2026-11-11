// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { setMe, type Me } from '@/lib/identity';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { RequireControl } from '../RequireControl';
import { WorkflowSupervision } from '../WorkflowSupervision';

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
    { timestamp: 't', status: 404, message: 'no', path: '/' },
    { status: 404 }
  );

let capabilities: Record<string, unknown> = {};
let ownership: Record<string, unknown> = {};
let supervisors: unknown[] = [];
let asked = '';
const server = setupServer(
  http.get('/api/supervision', ({ request }) => {
    asked = new URL(request.url).search;
    return ok(capabilities);
  }),
  http.get('/api/supervision/ownership', () => ok(ownership)),
  http.get('/api/supervisors', () => ok(supervisors)),
  http.get('/api/supervisor-engines', () =>
    ok([{ type: 'RULES', displayName: 'Rules', category: 'RULES', icon: null }])
  ),
  http.get('/api/supervisors/assignments', () => ok([]))
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  setMe(null);
  capabilities = {};
  supervisors = [];
  asked = '';
});
afterAll(() => server.close());

const wrap = (node: React.ReactNode) =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>{node}</MemoryRouter>
    </QueryClientProvider>
  );

const gated = (command: string, target = { execution: 'e1' }) =>
  wrap(
    <RequireControl perm="controlRuns" command={command} target={target}>
      <button type="button">Do it</button>
    </RequireControl>
  );

describe('RequireControl', () => {
  it('disables a command the owner does not let people send, with its reason', async () => {
    capabilities = { pause: { allowed: false, via: 'guard', reason: null } };
    gated('pause');
    await expect
      .poll(() =>
        screen.getByRole('button', { name: 'Do it' }).getAttribute('title')
      )
      .toBe('Controlled by guard. People cannot do this directly.');
    const button = screen.getByRole('button', { name: 'Do it' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute(
      'aria-description',
      'Controlled by guard. People cannot do this directly.'
    );
    expect(asked).toBe('?execution=e1');
  });

  it('uses the server reason, and says when nobody can do it', async () => {
    capabilities = {
      stop: { allowed: false, via: 'x', reason: 'Not during a deploy' },
      start: { allowed: false, via: 'default', reason: null },
    };
    gated('stop');
    await expect
      .poll(() =>
        screen.getByRole('button', { name: 'Do it' }).getAttribute('title')
      )
      .toBe('Not during a deploy');
    screen.getByRole('button', { name: 'Do it' }).click();
    gated('start', { session: 's', workflow: 'w', node: 'n' } as never);
    await expect
      .poll(() =>
        screen
          .getAllByRole('button', { name: 'Do it' })[1]
          .getAttribute('title')
      )
      .toBe('No supervisor can do this here');
    expect(asked).toBe('?node=n&session=s&workflow=w');
  });

  it('stops a click on a refused control that is not a native button', async () => {
    capabilities = { pause: { allowed: false, via: 'guard', reason: null } };
    wrap(
      <RequireControl
        perm="controlRuns"
        command="pause"
        target={{ execution: 'e1' }}
      >
        <a href="/runs/e1">Open run</a>
      </RequireControl>
    );
    await expect
      .poll(() =>
        screen.getByRole('link', { name: 'Open run' }).getAttribute('title')
      )
      .toBe('Controlled by guard. People cannot do this directly.');
    expect(
      fireEvent.click(screen.getByRole('link', { name: 'Open run' }))
    ).toBe(false);
  });

  it('falls back to the role when the command is allowed or the server lacks supervision', async () => {
    setMe({ permissions: { controlRuns: false } } as unknown as Me);
    capabilities = { pause: { allowed: true, via: 'default', reason: null } };
    gated('pause');
    const button = screen.getByRole('button', { name: 'Do it' });
    await expect
      .poll(() => button.getAttribute('title'))
      .toBe('Needs OPERATOR');
  });

  it('asks about the server when there is no target', async () => {
    server.use(
      http.get('/api/supervision', ({ request }) => {
        asked = new URL(request.url).search || 'none';
        return failed();
      })
    );
    gated('setLimits', {} as never);
    await expect.poll(() => asked).toBe('none');
    expect(screen.getByRole('button', { name: 'Do it' })).toBeEnabled();
  });
});

describe('WorkflowSupervision', () => {
  const open = async () => {
    const user = userEvent.setup();
    wrap(<WorkflowSupervision sessionId="s" workflowId="w" executionId="e1" />);
    await user.click(await screen.findByRole('button', { name: /Run owner:/ }));
    return screen.getByRole('dialog');
  };

  it('shows the run owner, who owns the nodes, and who can stop the run', async () => {
    ownership = {
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: true,
      run: 'lead',
      nodes: { 'build.a': 'lead', 'build.b': 'lead', smoke: 'default' },
    };
    capabilities = { stop: { allowed: true, via: 'lead', reason: null } };
    supervisors = [
      {
        id: 'lead',
        type: 'RULES',
        description: 'Keeps the release gates.',
        dryRun: true,
        system: true,
        parents: ['boss', 'chief'],
      },
    ];
    const dialog = await open();
    expect(
      screen.getByRole('button', { name: 'Run owner: lead' })
    ).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: 'lead' })).toHaveAttribute(
      'href',
      '/supervisors?supervisor=lead'
    );
    expect(within(dialog).getByText('build.a')).toBeInTheDocument();
    expect(within(dialog).getByText('build.b')).toBeInTheDocument();
    expect(await within(dialog).findByText('Rules')).toBeInTheDocument();
    expect(within(dialog).getByText('Dry run')).toBeInTheDocument();
    expect(within(dialog).getByText('Built in')).toBeInTheDocument();
    expect(
      within(dialog).getByText('Keeps the release gates.')
    ).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: 'boss' })).toHaveAttribute(
      'href',
      '/supervisors?supervisor=boss'
    );
    expect(
      within(dialog).getByText('Controls the whole run and 2 nodes')
    ).toBeInTheDocument();
    expect(within(dialog).getByText('Controls 1 node')).toBeInTheDocument();
    expect(await within(dialog).findByText('via lead')).toBeInTheDocument();
    await userEvent.setup().keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('says when nobody owns it, or nobody can control it, and who may stop', async () => {
    ownership = {
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: true,
      run: 'default',
      nodes: { a: 'default' },
    };
    capabilities = { stop: { allowed: true, via: 'default', reason: null } };
    const dialog = await open();
    expect(
      await within(dialog).findByText('Controls the whole run and 1 node')
    ).toBeInTheDocument();
    expect(await within(dialog).findByText('Stop')).toBeInTheDocument();
    expect(within(dialog).queryByText(/controllers/)).not.toBeInTheDocument();
  });

  it('lists each command: allowed, refused by the supervisor, or blocked by the role', async () => {
    setMe({
      role: 'VIEWER',
      permissions: { startRuns: true, controlRuns: false },
    } as unknown as Me);
    capabilities = {
      start: { allowed: true, via: 'default', reason: null },
      pause: { allowed: true, via: 'lead', reason: null },
      stop: { allowed: false, via: 'lead', reason: 'Held by lead' },
    };
    const dialog = await open();
    expect(
      await within(dialog).findByText('Needs OPERATOR; your key is VIEWER.')
    ).toBeInTheDocument();
    expect(within(dialog).getByText('Held by lead')).toBeInTheDocument();
    expect(within(dialog).getByText('Start a new run')).toBeInTheDocument();
    expect(within(dialog).queryByText('Restart')).not.toBeInTheDocument();
  });

  it('lists every node by name and finds one by search', async () => {
    const names = Array.from({ length: 13 }, (_, i) => `node${i}`);
    ownership = {
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: true,
      run: 'default',
      nodes: Object.fromEntries(
        [...names, 'node0.fail'].map((id) => [id, 'default'])
      ),
    };
    const dialog = await open();
    expect(await within(dialog).findByText('node1')).toBeInTheDocument();
    expect(within(dialog).getByText('node0.fail')).toBeInTheDocument();
    const user = userEvent.setup();
    const search = within(dialog).getByLabelText('Find a node');
    await user.type(search, 'fail');
    expect(within(dialog).queryByText('node1')).not.toBeInTheDocument();
    await user.clear(search);
    await user.type(search, 'zzz');
    expect(
      within(dialog).getByText('No node matches “zzz”.')
    ).toBeInTheDocument();
  });

  it('puts the run controller on top and the others below', async () => {
    ownership = {
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: true,
      run: 'lead',
      nodes: { a: 'lead', b: 'agent', c: 'default' },
    };
    const dialog = await open();
    expect(
      await within(dialog).findByText('Run controlled by')
    ).toBeInTheDocument();
    expect(
      within(dialog).getByText('2 others controlling nodes')
    ).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: 'agent' })).toHaveAttribute(
      'href',
      '/supervisors?supervisor=agent'
    );
  });

  it('says one other controller in the singular', async () => {
    ownership = {
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: true,
      run: 'lead',
      nodes: { b: 'agent' },
    };
    const dialog = await open();
    expect(
      await within(dialog).findByText('Also controlling nodes')
    ).toBeInTheDocument();
  });

  it('says when the default is off, and when stopping is refused', async () => {
    ownership = {
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: false,
      run: 'default',
      nodes: {},
    };
    capabilities = { stop: { allowed: false, via: 'default', reason: null } };
    const dialog = await open();
    expect(
      await within(dialog).findByText('Nobody can control this workflow')
    ).toBeInTheDocument();
    expect(
      within(dialog).getByText(/Default \(people\) is off/)
    ).toBeInTheDocument();
    expect(
      await within(dialog).findByText('No supervisor can do this here')
    ).toBeInTheDocument();
  });

  it('shows a refusal reason the server gives', async () => {
    ownership = {
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: true,
      run: 'g',
      nodes: {},
    };
    capabilities = { stop: { allowed: false, via: 'g', reason: 'Only g' } };
    const dialog = await open();
    expect(await within(dialog).findByText('Only g')).toBeInTheDocument();
  });

  it('says when the owners cannot be read, and shows the default before they load', async () => {
    server.use(http.get('/api/supervision/ownership', () => failed()));
    wrap(
      <WorkflowSupervision sessionId="s" workflowId="w" executionId={null} />
    );
    const user = userEvent.setup();
    await user.click(
      screen.getByRole('button', { name: 'Run owner: Default (people)' })
    );
    expect(
      await screen.findByText('Could not load who owns this workflow.')
    ).toBeInTheDocument();
  });

  it('shows loading while the owners load', async () => {
    server.use(
      http.get('/api/supervision/ownership', () => new Promise(() => {}))
    );
    wrap(
      <WorkflowSupervision sessionId="s" workflowId="w" executionId={null} />
    );
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: /Run owner/ }));
    expect(screen.getByText('Loading who owns what…')).toBeInTheDocument();
  });

  it('says the server named no commands when a run is open', async () => {
    ownership = {
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: true,
      run: 'default',
      nodes: {},
    };
    capabilities = {};
    const dialog = await open();
    expect(
      await within(dialog).findByText(
        'The server did not say which commands are allowed.'
      )
    ).toBeInTheDocument();
  });

  it('asks for a run to be opened when none is open', async () => {
    ownership = {
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: true,
      run: 'default',
      nodes: {},
    };
    capabilities = {};
    wrap(
      <WorkflowSupervision sessionId="s" workflowId="w" executionId={null} />
    );
    await userEvent
      .setup()
      .click(await screen.findByRole('button', { name: /Run owner/ }));
    const dialog = screen.getByRole('dialog');
    expect(
      await within(dialog).findByText(
        'Open a run to see which commands you can send to it.'
      )
    ).toBeInTheDocument();
  });
});
