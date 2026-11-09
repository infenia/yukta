// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  afterAll,
  afterEach,
  beforeEach,
} from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { Activity } from '../Activity';
import { groupDecisions } from '../../lib/activity';
import type { Decision } from '../../types';

const envelope = (data: unknown, status = 200) =>
  HttpResponse.json(
    {
      timestamp: 't',
      status,
      message: status < 400 ? 'ok' : 'down',
      data,
      path: '/',
    },
    { status }
  );

const decision = (over: Partial<Decision>): Decision => ({
  time: '2026-11-09T12:03:11Z',
  supervisorId: 'release-guard',
  outcome: 'applied',
  command: 'PauseWorkflowCommand',
  executionId: '8f2c41aa-0000',
  reason: null,
  ...over,
});

let decisions: Decision[];
let asked: string[];
let status: number;
const server = setupServer(
  http.get('/api/supervisors/decisions', ({ request }) => {
    asked.push(new URL(request.url).search);
    return envelope(status < 400 ? decisions : null, status);
  })
);
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  asked = [];
  status = 200;
  decisions = [
    decision({
      cause: 'initiative',
      because: '3 failures in 60 s',
      key: 'k1',
      workflowId: 'release-flow',
    }),
    ...['build.compile', 'build.test', 'build.lint'].map((nodeId, i) =>
      decision({
        supervisorId: 'build-agent',
        cause: 'event',
        command: 'RetryNodeCommand',
        key: `retry#e${i}`,
        nodeId,
        workflowId: 'build',
        because: 'flaky test',
        outcome: i === 2 ? 'not-owner' : 'applied',
        reason: i === 2 ? 'not owned' : null,
      })
    ),
    decision({
      supervisorId: 'release-lead',
      cause: 'answers',
      command: 'StopWorkflowCommand',
      outcome: 'awaiting-approval',
      reason: 'input request 41',
    }),
    decision({
      supervisorId: 'stall-watch',
      cause: 'tick',
      outcome: 'dry-run',
      reason: 'dry-run',
      nodeId: 'deploy.push',
      because: 'silent for 10 min',
    }),
    decision({ supervisorId: 'legacy', cause: null, outcome: 'rejected' }),
    decision({ supervisorId: 'odd', cause: 'other', key: 'solo#x' }),
  ];
});

const show = (supervisor?: string) =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <Activity supervisor={supervisor} />
    </QueryClientProvider>
  );

const rows = async () => {
  const list = await screen.findByRole('list', { name: 'Activity' });
  return within(list)
    .getAllByRole('listitem')
    .filter((li) => li.parentElement === list);
};

describe('the Activity list', () => {
  it('shows cause, target, command and outcome, with a result per target', async () => {
    show();
    const [own, retry, answered, tick, legacy, odd] = await rows();
    expect(own).toHaveTextContent('on its own');
    expect(own).toHaveTextContent(
      'release-guard → release-flow · run 8f2c41aa'
    );
    expect(own).toHaveTextContent('Pause');
    expect(own).toHaveTextContent('Reason: 3 failures in 60 s');

    expect(retry).toHaveTextContent('build-agent → 3 nodes');
    expect(retry).toHaveTextContent('2 of 3 applied');
    const perTarget = within(retry).getByRole('list', { name: 'Per target' });
    expect(perTarget).toHaveTextContent('build · build.lint');
    expect(perTarget).toHaveTextContent('not-owner: not owned');

    expect(answered).toHaveTextContent('answered');
    expect(answered).toHaveTextContent('awaiting-approval: input request 41');
    expect(answered).not.toHaveTextContent('Reason:');
    expect(tick).toHaveTextContent('tick');
    expect(tick).toHaveTextContent('deploy.push');
    expect(tick).toHaveTextContent('dry-run: dry-run');
    expect(legacy).toHaveTextContent('no cause given');
    expect(legacy).toHaveTextContent('rejected');
    expect(odd).toHaveTextContent('other');
    expect(asked).toEqual(['?limit=200']);
  });

  it('says when acting on its own gave no reason, and when every target applied', async () => {
    decisions = [
      decision({ cause: 'initiative' }),
      decision({ key: 'all#1', executionId: 'a' }),
      decision({ key: 'all#2', executionId: 'b' }),
    ];
    show();
    const [own, all] = await rows();
    expect(own).toHaveTextContent('Reason: none given');
    expect(all).toHaveTextContent('release-guard → 2 runs');
    expect(all).toHaveTextContent('applied');
    expect(all).not.toHaveTextContent('of 2 applied');
  });

  it('filters by supervisor, cause, outcome and workflow', async () => {
    const user = userEvent.setup();
    show();
    await rows();
    await user.selectOptions(screen.getByLabelText('Cause'), 'tick');
    expect(await rows()).toHaveLength(1);
    await user.selectOptions(screen.getByLabelText('Cause'), '');
    await user.selectOptions(screen.getByLabelText('Workflow'), 'build');
    expect(await rows()).toHaveLength(1);
    await user.selectOptions(screen.getByLabelText('Outcome'), 'dry-run');
    expect(
      screen.getByText('Nothing matches these filters.')
    ).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Outcome'), '');
    await user.selectOptions(screen.getByLabelText('Workflow'), '');
    await user.selectOptions(screen.getByLabelText('Supervisor'), 'legacy');
    expect(await rows()).toHaveLength(1);
  });

  it('for one supervisor asks for its decisions and has no supervisor filter', async () => {
    show('release-guard');
    await rows();
    expect(asked).toEqual(['?limit=200&supervisor=release-guard']);
    expect(screen.queryByLabelText('Supervisor')).toBeNull();
    expect(screen.getByText(/What release-guard did/)).toBeInTheDocument();
  });

  it('has an empty and a failed state, with retry', async () => {
    const user = userEvent.setup();
    decisions = [];
    const first = show();
    expect(await screen.findByText('No activity yet')).toBeInTheDocument();
    first.unmount();
    status = 500;
    show();
    expect(
      await screen.findByText('Failed to load the activity.')
    ).toBeInTheDocument();
    status = 200;
    decisions = [decision({})];
    await user.click(screen.getByRole('button', { name: /Retry/ }));
    expect(await rows()).toHaveLength(1);
  });
});

describe('groupDecisions', () => {
  it('groups by supervisor and key before #, keeping the order of the newest', () => {
    const groups = groupDecisions([
      decision({ key: 'a#1' }),
      decision({}),
      decision({ key: 'a#2' }),
      decision({ key: 'a#3', supervisorId: 'other' }),
    ]);
    expect(groups.map((g) => g.items.length)).toEqual([2, 1, 1]);
  });
});
