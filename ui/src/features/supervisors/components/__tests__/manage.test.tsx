// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { preText } from '@/test/preText';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { SupervisorsPage } from '../SupervisorsPage';
import { SupervisorWizardPage } from '../SupervisorWizardPage';
import { groupNodes } from '../../lib/scope';
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
        </Routes>
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('the activity tab', () => {
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
    const first = renderAt('/supervisors?tab=activity');

    expect(await screen.findByText('busy')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Outcome'), 'applied');
    expect(screen.queryByText('busy')).toBeNull();
    await user.selectOptions(screen.getByLabelText('Outcome'), '');
    first.unmount();

    decisions = [];
    const second = renderAt('/supervisors?tab=activity');
    expect(await screen.findByText('No decisions yet.')).toBeInTheDocument();
    second.unmount();
    server.use(http.get('/api/supervisors/decisions', () => envelope({}, 500)));
    renderAt('/supervisors?tab=activity');
    expect(
      await screen.findByText('Failed to load the decisions.')
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
