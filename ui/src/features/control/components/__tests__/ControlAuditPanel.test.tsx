// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import {
  ControlAuditPanel,
  rowKeys,
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

const asked: string[] = [];

const server = setupServer(
  http.get('/api/control/audit', ({ request }) => {
    asked.push(new URL(request.url).search);
    return fail
      ? HttpResponse.json({ message: 'x' }, { status: 500 })
      : ok(entries);
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  entries = [];
  fail = false;
  asked.length = 0;
  window.history.pushState({}, '', '/');
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

    expect(
      await screen.findByRole('cell', { name: 'key:bob' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('cell', { name: 'PauseWorkflowCommand' })
    ).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Applied' })).toBeInTheDocument();
    expect(screen.getAllByText('api').length).toBeGreaterThan(0);
    // a refusal is a badge with its reason beside it
    expect(screen.getAllByText('Refused', { selector: 'span' })).toHaveLength(
      2
    );
    expect(screen.getByText('deny: busy')).toBeInTheDocument();
  });

  it('names the workflow and session of a run and links to it, else shows the plain id', async () => {
    entries = [
      {
        time: '2026-10-14T10:00:00Z',
        executionId: 'exec-1234-5678',
        command: 'PauseWorkflowCommand',
        actor: 'key:bob',
        applied: true,
        reason: null,
        sessionId: 's1',
        workflowId: 'build',
      },
      {
        time: '2026-10-14T10:01:00Z',
        executionId: 'scheduler',
        command: 'SetLimits(1, 0)',
        actor: null,
        applied: true,
        reason: null,
      },
    ];
    renderWithProviders(<ControlAuditPanel />);

    expect(await screen.findByText('build')).toBeInTheDocument();
    expect(screen.getByText(/· s1/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'exec-123' })).toHaveAttribute(
      'href',
      '/sessions/s1/workflow/build?exec=exec-1234-5678'
    );
    expect(screen.getByText('scheduler')).toBeInTheDocument();
    expect(screen.getByText('–')).toBeInTheDocument();
  });

  it('filters the loaded commands by result, command and actor', async () => {
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
        actor: 'rules',
        applied: false,
        reason: 'busy',
      },
      {
        time: '2026-10-14T10:02:00Z',
        executionId: 'scheduler',
        command: 'SetLimits(1, 0)',
        actor: null,
        applied: true,
        reason: null,
      },
    ];
    const user = userEvent.setup();
    renderWithProviders(<ControlAuditPanel />);
    await screen.findByRole('cell', { name: 'key:bob' });

    await user.selectOptions(screen.getByLabelText('Result'), 'refused');
    expect(screen.getAllByRole('row')).toHaveLength(2);
    expect(
      screen.getByRole('cell', { name: 'StopWorkflowCommand' })
    ).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Result'), 'applied');
    expect(screen.getAllByRole('row')).toHaveLength(3);

    await user.selectOptions(screen.getByLabelText('Command'), 'SetLimits');
    expect(screen.getAllByRole('row')).toHaveLength(2);
    expect(screen.getByText('SetLimits(1, 0)')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Command'), '');
    await user.selectOptions(screen.getByLabelText('Actor'), 'key:bob');
    expect(screen.getAllByRole('row')).toHaveLength(2);

    await user.selectOptions(screen.getByLabelText('Result'), 'refused');
    expect(
      screen.getByText('No commands match the filters.')
    ).toBeInTheDocument();
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

describe('ControlAuditPanel filter and paging', () => {
  const many = (n: number): ControlAuditEntry[] =>
    Array.from({ length: n }, (_, i) => ({
      time: `2026-10-14T10:00:${String(i % 60).padStart(2, '0')}Z`,
      executionId: `e${i}`,
      command: 'PauseWorkflowCommand',
      actor: 'key:bob',
      applied: true,
      reason: null,
    }));

  it('asks for the commands of one run, and shows all again on request', async () => {
    window.history.pushState({}, '', '/control?execution=run-7');
    entries = many(1);
    renderWithProviders(<ControlAuditPanel />);
    expect(await screen.findByText('run-7')).toBeInTheDocument();
    expect(asked[0]).toBe('?limit=50&executionId=run-7');

    await userEvent.click(screen.getByRole('button', { name: 'Show all' }));
    await waitFor(() => expect(asked.at(-1)).toBe('?limit=50'));
    expect(asked.at(-1)).toBe('?limit=50');
    expect(screen.queryByText('run-7')).not.toBeInTheDocument();
  });

  it('offers more rows while the page is full, up to the limit', async () => {
    entries = many(50);
    renderWithProviders(<ControlAuditPanel />);
    await userEvent.click(
      await screen.findByRole('button', { name: 'Show more' })
    );
    await waitFor(() => expect(asked.at(-1)).toBe('?limit=100'));
  });

  it('hides Show more when the last page was not full', async () => {
    entries = many(3);
    renderWithProviders(<ControlAuditPanel />);
    await screen.findAllByText('PauseWorkflowCommand');
    expect(
      screen.queryByRole('button', { name: 'Show more' })
    ).not.toBeInTheDocument();
  });
});

describe('rowKeys', () => {
  const entry = (command: string): ControlAuditEntry => ({
    time: '2026-01-01T00:00:00',
    executionId: 'e1',
    command,
    actor: null,
    applied: true,
    reason: null,
  });

  it("keeps a row's key when newer rows arrive in front of it", () => {
    const old = entry('PAUSE');
    const before = rowKeys([old]);
    const after = rowKeys([entry('STOP'), old]);
    expect(after[1]).toBe(before[0]);
  });

  it('tells apart the same command sent twice in the same instant', () => {
    const [first, second] = rowKeys([entry('PAUSE'), entry('PAUSE')]);
    expect(first).not.toBe(second);
  });
});
