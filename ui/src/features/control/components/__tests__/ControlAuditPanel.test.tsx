// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import {
  ControlAuditPanel,
  type ControlAuditEntry,
} from '../ControlAuditPanel';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

let entries: ControlAuditEntry[] = [];
let fail = false;

const server = setupServer(
  http.get('/api/control/audit', () =>
    fail ? HttpResponse.json({ message: 'x' }, { status: 500 }) : ok(entries)
  )
);

beforeAll(() => server.listen());
afterEach(() => {
  entries = [];
  fail = false;
});
afterAll(() => server.close());

describe('ControlAuditPanel', () => {
  it('shows who sent which command and its result', async () => {
    entries = [
      {
        time: '2026-10-14T10:00:00Z',
        executionId: 'e1',
        command: 'PauseWorkflowCommand',
        actor: 'key:bob',
        applied: true,
        reason: null,
      },
      {
        time: '2026-10-14T10:01:00Z',
        executionId: 'e2',
        command: 'StopWorkflowCommand',
        actor: null,
        applied: false,
        reason: 'deny: busy',
      },
      {
        time: '2026-10-14T10:02:00Z',
        executionId: 'e3',
        command: 'SetLimits(1, 0)',
        actor: 'rules',
        applied: false,
        reason: null,
      },
    ];
    renderWithProviders(<ControlAuditPanel />);

    expect(await screen.findByText('key:bob')).toBeInTheDocument();
    expect(screen.getByText('PauseWorkflowCommand')).toBeInTheDocument();
    expect(screen.getByText('Applied')).toBeInTheDocument();
    expect(screen.getByText('api')).toBeInTheDocument();
    expect(screen.getByText('Refused: deny: busy')).toBeInTheDocument();
    expect(screen.getByText('Refused:')).toBeInTheDocument();
  });

  it('says so when nothing was recorded', async () => {
    renderWithProviders(<ControlAuditPanel />);

    expect(
      await screen.findByText('No control commands recorded yet.')
    ).toBeInTheDocument();
  });

  it('shows an error when loading fails', async () => {
    fail = true;
    renderWithProviders(<ControlAuditPanel />);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to load the control audit'
    );
  });

  it('retries after a failure', async () => {
    fail = true;
    renderWithProviders(<ControlAuditPanel />);
    await screen.findByRole('alert');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(
      await screen.findByText('No control commands recorded yet.')
    ).toBeInTheDocument();
  });
});
