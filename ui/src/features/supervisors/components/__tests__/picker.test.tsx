// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { SupervisorPicker } from '../SupervisorPicker';
import type { SupervisorRef } from '../../types';
import { createTestQueryClient } from '@/test/utils/testUtils';

const ok = (data: unknown, status = 200) =>
  HttpResponse.json(
    { timestamp: 't', status, message: 'ok', data, path: '/' },
    { status }
  );

const server = setupServer(
  http.get('/api/supervisors', () =>
    ok([
      { id: 'guard', type: 't', system: false },
      { id: 'watch', type: 't', system: false },
      { id: 'rules', type: 't', system: true },
    ])
  ),
  http.get('/api/supervisor-engines', () =>
    ok([
      {
        type: 'failure-guard',
        displayName: 'Failure guard',
        category: 'GUARD',
        kind: 'SUPERVISOR',
        icon: null,
      },
    ])
  ),
  http.get('/api/supervisor-engines/:type', () =>
    ok({
      type: 'failure-guard',
      displayName: 'Failure guard',
      description: '',
      usagePattern: '',
      category: 'GUARD',
      kind: 'SUPERVISOR',
      configSchema: [],
      commonSchema: [],
      defaultAllowedCommands: null,
      exampleSettings: {},
    })
  ),
  http.post('/api/supervisors/validate', () => ok({ valid: true, errors: [] })),
  http.post('/api/supervisors', () => ok({}, 201))
);
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

let latest: SupervisorRef[] = [];
afterEach(() => {
  latest = [];
});
function Harness({
  start = [],
  nodeIds,
  errors,
}: {
  start?: SupervisorRef[];
  nodeIds?: string[];
  errors?: string[];
}) {
  const [value, setValue] = useState(start);
  return (
    <SupervisorPicker
      value={value}
      onChange={(next) => {
        latest = next;
        setValue(next);
      }}
      nodeIds={nodeIds}
      errors={errors}
    />
  );
}

const renderPicker = (props: Parameters<typeof Harness>[0] = {}) =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <Harness {...props} />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('SupervisorPicker', () => {
  it('adds, changes, pauses and removes supervisors', async () => {
    const user = userEvent.setup();
    renderPicker();

    const add = screen.getByRole('button', { name: 'Add supervisor' });
    await waitFor(() => expect(add).toBeEnabled());
    await user.click(add);
    expect(latest).toEqual([{ supervisor: 'guard' }]);
    await user.selectOptions(screen.getByLabelText('Supervisor 1'), 'watch');
    await user.click(screen.getByLabelText('Runs'));
    expect(latest).toEqual([{ supervisor: 'watch', enabled: false }]);
    expect(screen.queryByRole('option', { name: 'rules' })).toBeNull();
    await user.click(add);
    expect(latest).toHaveLength(2);
    await user.selectOptions(screen.getByLabelText('Supervisor 2'), 'guard');
    expect(latest[0].supervisor).toBe('watch');
    await user.click(screen.getByLabelText('Remove supervisor 1'));
    expect(latest).toEqual([{ supervisor: 'guard' }]);
  });

  it('cannot add when every supervisor is used, and shows one that no longer exists', async () => {
    renderPicker({
      start: [
        { supervisor: 'guard' },
        { supervisor: 'watch' },
        { supervisor: 'gone' },
      ],
    });
    expect(
      await screen.findByRole('option', { name: 'gone' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Add supervisor' })
    ).toBeDisabled();
  });

  it('picks the whole workflow or chosen nodes, grouped by prefix', async () => {
    const user = userEvent.setup();
    renderPicker({
      start: [{ supervisor: 'guard' }],
      nodeIds: ['build.a', 'build.b', 'end'],
    });

    await user.click(screen.getByLabelText('Selected nodes'));
    expect(latest[0].nodes).toEqual([]);
    expect(screen.getByText('build.*')).toBeInTheDocument();
    expect(screen.getByText('No prefix')).toBeInTheDocument();
    await user.click(screen.getByLabelText('build.a'));
    await user.click(screen.getByLabelText('build.b'));
    await user.click(screen.getByLabelText('build.a'));
    expect(latest[0].nodes).toEqual(['build.b']);
    await user.click(screen.getByLabelText('Whole workflow'));
    expect(latest[0].nodes).toBeNull();
    expect(screen.queryByLabelText('build.b')).toBeNull();
  });

  it('shows what the server said about the refs', () => {
    renderPicker({ errors: ['assignments[0].supervisor: no supervisor'] });
    expect(screen.getByRole('alert')).toHaveTextContent('no supervisor');
  });

  it('creates a new supervisor in a drawer and adds it', async () => {
    const user = userEvent.setup();
    renderPicker();

    await user.click(screen.getByRole('button', { name: 'Create new' }));
    expect(
      await screen
        .findByText('New supervisor', { selector: '[data-slot], h2, div' })
        .catch(() => null)
    ).not.toBeUndefined();
    await user.selectOptions(
      await screen.findByLabelText('Supervisor type'),
      'failure-guard'
    );
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.type(screen.getByLabelText('Name'), 'fresh');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(latest).toEqual([{ supervisor: 'fresh' }]));
  });

  it('closes the drawer without adding anything', async () => {
    const user = userEvent.setup();
    renderPicker();
    await user.click(screen.getByRole('button', { name: 'Create new' }));
    await screen.findByLabelText('Supervisor type');
    await user.keyboard('{Escape}');
    await waitFor(() =>
      expect(screen.queryByLabelText('Supervisor type')).toBeNull()
    );
    expect(latest).toEqual([]);
  });
});
