// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  afterAll,
  afterEach,
  vi,
} from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { AttachDialog } from '../AttachDialog';
import { renderWithProviders } from '@/test/utils/testUtils';

const envelope = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const supervisor = (id: string, system = false) => ({
  id,
  type: 'failure-guard',
  description: '',
  settings: {},
  dryRun: false,
  system,
  attachments: 0,
  applied: 0,
  rejected: 0,
  recorded: 0,
  rateLimited: 0,
});

let supervisors: unknown[] = [];

const server = setupServer(
  http.get('/api/supervisors', () => envelope(supervisors)),
  http.get('/api/sessions/summaries', () =>
    envelope([{ sessionId: 's1', name: 'Alpha' }])
  ),
  http.get('/api/sessions/:id/workflows', () =>
    envelope([
      {
        workflowId: 'w1',
        description: '',
        nodeCount: 1,
        edgeCount: 0,
        status: null,
      },
    ])
  )
);

beforeAll(() => server.listen());
afterAll(() => server.close());
afterEach(() => {
  server.resetHandlers();
  supervisors = [];
});

describe('AttachDialog', () => {
  it('asks which supervisor to attach, from the configured ones', async () => {
    const user = userEvent.setup();
    supervisors = [
      supervisor('guard'),
      supervisor('other'),
      supervisor('rules', true),
    ];
    renderWithProviders(<AttachDialog onClose={vi.fn()} />);

    const choose = screen.getByRole('combobox', { name: 'Choose supervisor' });
    expect(
      await screen.findByRole('option', { name: 'other' })
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('option', { name: 'rules' })
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('option', { name: 'Create a supervisor first' })
    ).not.toBeInTheDocument();

    await user.selectOptions(choose, 'other');
    expect(await screen.findByText('Attach other')).toBeInTheDocument();
  });

  it('says to create a supervisor first when none is configured', async () => {
    supervisors = [supervisor('rules', true)];
    renderWithProviders(<AttachDialog onClose={vi.fn()} />);

    expect(
      await screen.findByRole('option', { name: 'Create a supervisor first' })
    ).toBeInTheDocument();
    expect(screen.getByText('Attach a supervisor')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Attach' })).toBeDisabled();
  });

  it('opens on the session it is given', async () => {
    renderWithProviders(
      <AttachDialog
        supervisorId="guard"
        preset={{ sessionId: 's1' }}
        onClose={vi.fn()}
      />
    );

    await screen.findByRole('option', { name: 'Alpha' });
    expect(
      screen.getByRole('combobox', { name: 'Choose session' })
    ).toHaveValue('s1');
    expect(screen.getByLabelText('Scope preview')).toHaveTextContent('s1');
  });

  it('opens on the workflow it is given, at that kind of scope', async () => {
    renderWithProviders(
      <AttachDialog
        supervisorId="guard"
        preset={{ sessionId: 's1', workflowId: 'w1' }}
        onClose={vi.fn()}
      />
    );

    await screen.findByRole('option', { name: 'w1' });
    expect(screen.getByRole('radio', { name: 'Workflow' })).toBeChecked();
    expect(
      screen.getByRole('combobox', { name: 'Choose workflow' })
    ).toHaveValue('w1');
  });

  it('starts with no session and no scope when nothing is given', async () => {
    renderWithProviders(
      <AttachDialog supervisorId="guard" onClose={vi.fn()} />
    );

    expect(
      await screen.findByRole('combobox', { name: 'Choose session' })
    ).toHaveValue('');
    expect(screen.getByLabelText('Scope preview')).toHaveTextContent('—');
  });
});
