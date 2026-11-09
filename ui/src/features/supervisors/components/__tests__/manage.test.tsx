// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { preText } from '@/test/preText';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { SupervisorsPage } from '../SupervisorsPage';
import { SupervisorDetailPage } from '../SupervisorDetailPage';
import { SupervisorWizardPage } from '../SupervisorWizardPage';
import { groupNodes } from '../AttachDialog';
import { getAttachments } from '../../api/supervisors';
import { createTestQueryClient } from '@/test/utils/testUtils';

const envelope = (data: unknown, status = 200) =>
  HttpResponse.json(
    { timestamp: 't', status, message: 'ok', data, path: '/' },
    { status }
  );

const field = (name: string, type = 'STRING', required = false) => ({
  name,
  type,
  required,
  defaultValue: null,
  options: [],
  description: '',
});

const guard = {
  id: 'guard',
  type: 'failure-guard',
  description: 'Stops runs',
  settings: { failures: 3, token: '***' },
  dryRun: false,
  system: false,
  attachments: 2,
  applied: 4,
  rejected: 1,
  recorded: 0,
  rateLimited: 0,
};
const system = { ...guard, id: 'rules', type: 'RuleSupervisor', system: true };

let supervisors: unknown[] = [guard, system];
let attachments: unknown[] = [];
let decisions: unknown[] = [];
let writes: string[] = [];
let validation: { valid: boolean; errors: unknown[] } = {
  valid: true,
  errors: [],
};

const server = setupServer(
  http.get('/api/supervisor-engines', () =>
    envelope([
      {
        type: 'failure-guard',
        displayName: 'Failure guard',
        category: 'RULES',
        icon: 'shield',
      },
    ])
  ),
  http.get('/api/supervisor-engines/:type', ({ params }) =>
    envelope({
      type: params.type,
      displayName: 'Failure guard',
      description: 'd',
      usagePattern: '',
      category: 'RULES',
      configSchema: [field('failures', 'NUMBER', true)],
      commonSchema: [field('maxRisk')],
      defaultAllowedCommands: null,
      exampleSettings: {},
    })
  ),
  http.get('/api/supervisors', () => envelope(supervisors)),
  http.get('/api/supervisors/decisions', () => envelope(decisions)),
  http.get('/api/supervisors/assignments', ({ request }) => {
    const source = new URL(request.url).searchParams.get('source');
    return envelope(
      source
        ? (attachments as { source: string }[]).filter(
            (a) => a.source === source
          )
        : attachments
    );
  }),
  http.get('/api/supervisors/:id/assignments', () => envelope(attachments)),
  http.put('/api/supervisors/:id/assignments', async ({ params, request }) => {
    writes.push(`assign ${String(params.id)} ${await request.text()}`);
    return envelope([]);
  }),
  http.post('/api/supervisors/validate', () => envelope(validation)),
  http.post('/api/supervisors', async ({ request }) => {
    writes.push(`create ${await request.text()}`);
    return envelope({}, 201);
  }),
  http.get('/api/supervisors/:id', ({ params }) =>
    params.id === 'rules'
      ? envelope(system)
      : params.id === 'plain'
        ? envelope({ ...guard, id: 'plain', description: null })
        : envelope(guard)
  ),
  http.put('/api/supervisors/:id', async ({ request }) => {
    writes.push(`replace ${await request.text()}`);
    return envelope({});
  }),
  http.delete('/api/supervisors/:id', ({ params }) => {
    writes.push(`delete ${String(params.id)}`);
    return envelope(null);
  }),
  http.get('/api/sessions/summaries', () =>
    envelope([
      { sessionId: 's1', name: 'Alpha' },
      { sessionId: 's2', name: '' },
    ])
  ),
  http.get('/api/sessions/:id/workflows', () =>
    envelope([
      {
        workflowId: 'w1',
        description: '',
        nodeCount: 3,
        edgeCount: 2,
        status: null,
      },
    ])
  ),
  http.get('/api/sessions/:id/workflows/:w', () =>
    envelope({
      nodes: [{ nodeId: 'build.a' }, { nodeId: 'build.b' }, { nodeId: 'end' }],
    })
  )
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  supervisors = [guard, system];
  attachments = [];
  decisions = [];
  writes = [];
  validation = { valid: true, errors: [] };
});
afterAll(() => server.close());

function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="where">{pathname + search}</output>;
}

const renderAt = (url: string) =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/supervisors" element={<SupervisorsPage />} />
          <Route path="/supervisors/new" element={<SupervisorWizardPage />} />
          <Route
            path="/supervisors/:supervisorId/edit"
            element={<SupervisorWizardPage />}
          />
          <Route
            path="/supervisors/:supervisorId"
            element={<SupervisorDetailPage />}
          />
        </Routes>
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('the supervisors list', () => {
  it('lists configured and system supervisors', async () => {
    renderAt('/supervisors?tab=supervisors');

    expect(await screen.findByText('guard')).toBeInTheDocument();
    expect(screen.getByText('System · read-only')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'New supervisor' })
    ).toHaveAttribute('href', '/supervisors/new');
    expect(screen.getAllByText('2 attached')).toHaveLength(2);
  });

  it('shows the empty and failed states', async () => {
    supervisors = [];
    const first = renderAt('/supervisors?tab=supervisors');
    expect(await screen.findByText('No supervisors yet')).toBeInTheDocument();
    first.unmount();
    server.use(http.get('/api/supervisors', () => envelope({}, 500)));
    renderAt('/supervisors?tab=supervisors');
    expect(
      await screen.findByText('Failed to load the supervisors.')
    ).toBeInTheDocument();
  });

  it('marks dry-run supervisors', async () => {
    supervisors = [{ ...guard, dryRun: true }];
    renderAt('/supervisors?tab=supervisors');
    expect(await screen.findByText('Dry run')).toBeInTheDocument();
  });
});

describe('the attachments and decisions tabs', () => {
  const manual = {
    id: 'a1',
    supervisorId: 'guard',
    scope: 'session:s1',
    enabled: true,
    source: 'MANUAL',
    originSessionId: null,
    originWorkflowId: null,
  };
  const declared = {
    ...manual,
    id: 'def-1',
    scope: 'workflow:s1/w1//build.*',
    source: 'DEFINITION',
    originSessionId: 's1',
    originWorkflowId: 'w1',
  };
  const sessionLevel = { ...declared, id: 'def-2', originWorkflowId: null };
  const bean = {
    ...manual,
    id: 'rules',
    scope: 'global',
    source: 'SYSTEM',
    enabled: false,
  };

  it('lists every attachment with its source and origin, and filters', async () => {
    attachments = [manual, declared, sessionLevel, bean];
    const user = userEvent.setup();
    renderAt('/supervisors?tab=attachments');

    expect(await screen.findAllByText('Edit the definition')).toHaveLength(2);
    expect(screen.getByRole('link', { name: 's1 › w1' })).toHaveAttribute(
      'href',
      '/sessions/s1/workflow/w1'
    );
    expect(screen.getByRole('link', { name: 's1' })).toHaveAttribute(
      'href',
      '/sessions/s1'
    );
    expect(screen.getByText('Set by the server')).toBeInTheDocument();
    expect(screen.getByText(/disabled/)).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Scope'), 'global');
    expect(screen.queryByText('Edit the definition')).toBeNull();
    await user.selectOptions(screen.getByLabelText('Scope'), 'session');
    expect(screen.getAllByRole('row').length).toBeGreaterThan(1);
    await user.selectOptions(screen.getByLabelText('Scope'), 'workflow');
    await user.selectOptions(screen.getByLabelText('Source'), 'DEFINITION');
    await waitFor(() =>
      expect(screen.queryByText('Set by the server')).toBeNull()
    );
  });

  it('pauses and detaches a manual attachment, and says when there are none', async () => {
    attachments = [manual];
    const user = userEvent.setup();
    renderAt('/supervisors?tab=attachments');

    await user.click(await screen.findByRole('button', { name: 'Disable' }));
    await waitFor(() =>
      expect(writes).toContain(
        'assign guard [{"target":"session:s1","enabled":false,"locked":false}]'
      )
    );
    await user.click(screen.getByRole('button', { name: 'Detach' }));
    await waitFor(() => expect(writes).toContain('assign guard []'));
  });

  it('says when there are no attachments and when they cannot be read', async () => {
    const first = renderAt('/supervisors?tab=attachments');
    expect(await screen.findByText('No attachments.')).toBeInTheDocument();
    first.unmount();
    server.use(
      http.get('/api/supervisors/assignments', () => envelope({}, 500))
    );
    renderAt('/supervisors?tab=attachments');
    expect(
      await screen.findByText('Failed to load the attachments.')
    ).toBeInTheDocument();
  });

  it('lists decisions, filters them by outcome, and handles the empty and failed cases', async () => {
    decisions = [
      {
        time: 't1',
        supervisorId: 'a1',
        outcome: 'applied',
        command: 'PauseWorkflowCommand',
        executionId: 'e1',
        reason: null,
      },
      {
        time: 't2',
        supervisorId: 'a1',
        outcome: 'rejected',
        command: 'StopWorkflowCommand',
        executionId: 'e2',
        reason: 'busy',
      },
    ];
    const user = userEvent.setup();
    const first = renderAt('/supervisors?tab=decisions');

    expect(await screen.findByText('busy')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Outcome'), 'applied');
    expect(screen.queryByText('busy')).toBeNull();
    await user.selectOptions(screen.getByLabelText('Outcome'), '');
    first.unmount();

    decisions = [];
    const second = renderAt('/supervisors?tab=decisions');
    expect(await screen.findByText('No decisions yet.')).toBeInTheDocument();
    second.unmount();
    server.use(http.get('/api/supervisors/decisions', () => envelope({}, 500)));
    renderAt('/supervisors?tab=decisions');
    expect(
      await screen.findByText('Failed to load the decisions.')
    ).toBeInTheDocument();
  });
});

describe('a supervisor', () => {
  it('shows its settings, attachments and decisions, and attaches it', async () => {
    attachments = [
      {
        id: 'a1',
        supervisorId: 'guard',
        scope: 'global',
        enabled: true,
        source: 'MANUAL',
        originSessionId: null,
        originWorkflowId: null,
      },
    ];
    const user = userEvent.setup();
    renderAt('/supervisors/guard');

    expect(
      await screen.findByRole('heading', { name: 'guard' })
    ).toBeInTheDocument();
    expect(screen.getByText(preText(/"token": "\*\*\*"/))).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'attachments' }));
    expect(await screen.findByText('Global')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'decisions' }));
    expect(await screen.findByText('No decisions yet.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Attach' }));
    const dialog = await screen.findByRole('dialog');
    // a session: choose it, the scope previews
    await within(dialog).findByRole('option', { name: 'Alpha' });
    await user.selectOptions(
      within(dialog).getByLabelText('Choose session'),
      's1'
    );
    expect(within(dialog).getByLabelText('Scope preview')).toHaveTextContent(
      'session:s1'
    );
    // nodes of a workflow, grouped by prefix
    await user.click(within(dialog).getByLabelText('Nodes'));
    await user.selectOptions(
      await within(dialog).findByLabelText('Choose workflow'),
      'w1'
    );
    await user.click(await within(dialog).findByLabelText('build.a'));
    await user.click(within(dialog).getByLabelText('build.b'));
    await user.click(within(dialog).getByLabelText('build.b'));
    expect(within(dialog).getByText('build.*')).toBeInTheDocument();
    expect(within(dialog).getByText('No prefix')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Scope preview')).toHaveTextContent(
      'workflow:s1/w1//build.a'
    );
    await user.click(within(dialog).getByRole('button', { name: 'Attach' }));
    await waitFor(() =>
      expect(writes[0]).toContain('"target":"workflow:s1/w1//build.a"')
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('attaches globally, changes kind and cancels', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors/guard');
    await user.click(await screen.findByRole('button', { name: 'Attach' }));
    const dialog = await screen.findByRole('dialog');

    await user.click(within(dialog).getByLabelText('Workflow'));
    await within(dialog).findByRole('option', { name: 'Alpha' });
    await user.selectOptions(
      within(dialog).getByLabelText('Choose session'),
      's2'
    );
    await user.selectOptions(
      await within(dialog).findByLabelText('Choose workflow'),
      'w1'
    );
    expect(within(dialog).getByLabelText('Scope preview')).toHaveTextContent(
      'workflow:s2/w1'
    );
    await user.click(within(dialog).getByLabelText('Global'));
    expect(within(dialog).queryByLabelText('Choose session')).toBeNull();
    await user.click(within(dialog).getByLabelText('Start now'));
    await user.click(within(dialog).getByRole('button', { name: 'Attach' }));
    await waitFor(() => expect(writes[0]).toContain('"target":"global"'));
    expect(writes[0]).toContain('"enabled":false');
  });

  it('says when attaching needs an admin key or fails', async () => {
    server.use(
      http.put('/api/supervisors/:id/assignments', () => envelope({}, 403))
    );
    const user = userEvent.setup();
    renderAt('/supervisors/guard');
    await user.click(await screen.findByRole('button', { name: 'Attach' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByLabelText('Global'));
    await user.click(within(dialog).getByRole('button', { name: 'Attach' }));
    expect(
      await within(dialog).findByText(
        'Attaching supervisors needs an ADMIN key.'
      )
    ).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows no actions for a system supervisor', async () => {
    renderAt('/supervisors/rules');
    expect(await screen.findByText(/read-only/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
  });

  it('edits through a link and deletes, going back to the list', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors/guard');
    expect(await screen.findByRole('link', { name: 'Edit' })).toHaveAttribute(
      'href',
      '/supervisors/guard/edit'
    );
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(writes).toContain('delete guard'));
    await waitFor(() =>
      expect(screen.getByLabelText('where')).toHaveTextContent(
        '/supervisors?tab=supervisors'
      )
    );
  });

  it('says why a delete failed and when the supervisor cannot be read', async () => {
    server.use(http.delete('/api/supervisors/:id', () => envelope({}, 409)));
    const user = userEvent.setup();
    const first = renderAt('/supervisors/guard');
    await user.click(await screen.findByRole('button', { name: 'Delete' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    first.unmount();
    server.use(http.get('/api/supervisors/:id', () => envelope({}, 500)));
    renderAt('/supervisors/guard');
    expect(
      await screen.findByText('Failed to load the supervisor.')
    ).toBeInTheDocument();
  });
});

describe('the wizard', () => {
  it('walks through the steps and creates a supervisor, validating as it goes', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors/new');

    await screen.findByRole('option', { name: 'Failure guard' });
    await user.selectOptions(
      screen.getByLabelText('Supervisor type'),
      'failure-guard'
    );
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    await user.type(screen.getByLabelText('Name'), 'mine');
    await user.type(screen.getByLabelText('Description'), 'for tests');
    await user.type(await screen.findByLabelText(/failures/), '5');
    await user.click(screen.getByLabelText('Advanced: JSON'));
    await user.click(screen.getByLabelText('Settings JSON'));
    await user.paste(' ');
    await user.click(screen.getByLabelText('Advanced: JSON'));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByLabelText(/Dry run/)).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText(preText(/"dryRun": true/))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Back' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(writes[0]).toContain('"id":"mine"'));
    expect(writes[0]).toContain('"dryRun":true');
    await waitFor(() =>
      expect(screen.getByLabelText('where')).toHaveTextContent(
        '/supervisors/mine'
      )
    );
  });

  it('lets the wizard turn off the dry run before it is reviewed', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors/new');

    await screen.findByRole('option', { name: 'Failure guard' });
    await user.selectOptions(
      screen.getByLabelText('Supervisor type'),
      'failure-guard'
    );
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.type(screen.getByLabelText('Name'), 'live');
    await user.type(screen.getByLabelText('Description'), 'goes live');
    await user.type(await screen.findByLabelText(/failures/), '5');
    await user.click(screen.getByRole('button', { name: 'Next' }));

    const dryRun = screen.getByLabelText(/Dry run/);
    expect(dryRun).toBeChecked();
    await user.click(dryRun);
    expect(dryRun).not.toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText(preText(/"dryRun": false/))).toBeInTheDocument();
  });

  it('shows the field errors the server finds and blocks next and save', async () => {
    validation = {
      valid: false,
      errors: [{ field: 'settings.failures', message: 'must be at least 1' }],
    };
    const user = userEvent.setup();
    renderAt('/supervisors/new?type=failure-guard');

    await user.type(await screen.findByLabelText('Name'), 'x');
    expect(await screen.findByText('settings.failures')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  it('takes the settings as JSON and ignores half-typed text', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors/new?type=failure-guard');
    await user.type(await screen.findByLabelText('Name'), 'j');
    await user.click(screen.getByLabelText('Advanced: JSON'));
    const box = screen.getByLabelText('Settings JSON');
    await user.clear(box);
    await user.click(box);
    await user.paste('{ nope');
    await user.clear(box);
    await user.paste('[1]');
    await user.clear(box);
    await user.paste('{"failures": 7}');
    await user.click(screen.getByLabelText('Advanced: JSON'));
    expect(await screen.findByDisplayValue('7')).toBeInTheDocument();
  });

  it('edits an existing supervisor and saves it under the same name', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors/guard/edit');

    expect(
      await screen.findByRole('heading', { name: 'Edit guard' })
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(writes[0]).toContain('replace'));
    expect(writes[0]).toContain('"id":"guard"');
  });

  it('shows a save that fails and an edit that cannot be loaded', async () => {
    server.use(http.post('/api/supervisors', () => envelope({}, 409)));
    const user = userEvent.setup();
    const first = renderAt('/supervisors/new?type=failure-guard');
    await user.type(await screen.findByLabelText('Name'), 'dup');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/409|ok|Conflict/i)).toBeInTheDocument();
    first.unmount();
    server.use(http.get('/api/supervisors/:id', () => envelope({}, 500)));
    renderAt('/supervisors/guard/edit');
    expect(
      await screen.findByText('Failed to load the supervisor.')
    ).toBeInTheDocument();
  });
});

describe('small cases', () => {
  it('enables a disabled manual attachment', async () => {
    attachments = [
      {
        id: 'a2',
        supervisorId: 'guard',
        scope: 'global',
        enabled: false,
        source: 'MANUAL',
        originSessionId: null,
        originWorkflowId: null,
      },
    ];
    const user = userEvent.setup();
    renderAt('/supervisors?tab=attachments');
    await user.click(await screen.findByRole('button', { name: 'Enable' }));
    await waitFor(() =>
      expect(writes).toContain(
        'assign guard [{"target":"global","enabled":true,"locked":false}]'
      )
    );
  });

  it('shows and edits a supervisor without a description', async () => {
    const first = renderAt('/supervisors/plain');
    expect(await screen.findByText('failure-guard')).toBeInTheDocument();
    first.unmount();
    renderAt('/supervisors/plain/edit');
    expect(await screen.findByLabelText('Description')).toHaveValue('');
  });

  it('shows no attachments while they cannot be read', async () => {
    server.use(
      http.get('/api/supervisors/:id/assignments', () => envelope({}, 500))
    );
    const user = userEvent.setup();
    renderAt('/supervisors/guard');
    await user.click(await screen.findByRole('tab', { name: 'attachments' }));
    expect(await screen.findByText('No attachments.')).toBeInTheDocument();
  });

  it('asks for attachments of a session and workflow by source', async () => {
    let asked = '';
    server.use(
      http.get('/api/supervisors/assignments', ({ request }) => {
        asked = new URL(request.url).search;
        return envelope([]);
      })
    );
    await getAttachments({ session: 's1', workflow: 'w1', source: 'MANUAL' });
    expect(asked).toBe('?session=s1&workflow=w1&source=MANUAL');
  });
});

describe('groupNodes', () => {
  it('groups by the part before the first dot, in order', () => {
    expect(groupNodes(['b.x', 'a', 'b.y'])).toEqual([
      ['b', ['b.x', 'b.y']],
      ['', ['a']],
    ]);
  });
});
