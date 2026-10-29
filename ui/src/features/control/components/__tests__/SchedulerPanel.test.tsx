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
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import {
  MockEventSource,
  installMockEventSource,
} from '@/test/mocks/MockEventSource';
import { ControlEventsProvider } from '../../context/ControlEvents';
import { limitText, parseLimit } from '../../api/scheduler';
import { SchedulerPanel } from '../SchedulerPanel';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

let limits = {
  maxConcurrent: 4,
  maxPerWorkflow: 0,
  running: 3,
  queued: 1,
  queueCapacity: 100,
};
let readFails = false;
let postStatus = 200;
const posts: string[] = [];
let reads = 0;

const server = setupServer(
  http.get('/api/scheduler/limits', () => {
    reads++;
    return readFails
      ? HttpResponse.json({ message: 'down' }, { status: 500 })
      : ok(limits);
  }),
  http.post('/api/scheduler/limits', ({ request }) => {
    posts.push(new URL(request.url).search);
    if (postStatus !== 200) {
      return HttpResponse.json({ message: 'refused' }, { status: postStatus });
    }
    const q = new URL(request.url).searchParams;
    limits = {
      ...limits,
      maxConcurrent: Number(q.get('maxConcurrent')),
      maxPerWorkflow: Number(q.get('maxPerWorkflow')),
    };
    return ok({});
  })
);

beforeAll(() => server.listen());
beforeEach(() => installMockEventSource());
afterEach(() => {
  limits = {
    maxConcurrent: 4,
    maxPerWorkflow: 0,
    running: 3,
    queued: 1,
    queueCapacity: 100,
  };
  readFails = false;
  postStatus = 200;
  posts.length = 0;
  reads = 0;
});
afterAll(() => server.close());

const panel = () =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <ControlEventsProvider>
        <SchedulerPanel />
      </ControlEventsProvider>
    </QueryClientProvider>
  );

describe('limit helpers', () => {
  it('reads 0 as unlimited and accepts only whole numbers', () => {
    expect(limitText(0)).toBe('Unlimited');
    expect(limitText(4)).toBe('4');
    expect(parseLimit(' 12 ')).toBe(12);
    expect(parseLimit('0')).toBe(0);
    for (const bad of ['', '-1', '1.5', 'x'])
      expect(parseLimit(bad)).toBeNull();
  });
});

describe('SchedulerPanel', () => {
  it('shows the limits, the use, and a usage bar only when there is a global limit', async () => {
    panel();
    expect(await screen.findByText('Max per workflow')).toBeInTheDocument();
    expect(screen.getByText('Unlimited')).toBeInTheDocument();
    expect(screen.getByText('100')).toBeInTheDocument();
    expect(screen.getByLabelText('Slots in use')).toHaveAttribute('value', '3');
  });

  it('has no bar when unlimited, and clamps it when more run than the limit', async () => {
    limits = { ...limits, maxConcurrent: 0 };
    const first = panel();
    await screen.findByText('Max per workflow');
    expect(screen.queryByLabelText('Slots in use')).not.toBeInTheDocument();
    first.unmount();

    limits = { ...limits, maxConcurrent: 2, running: 5 };
    panel();
    expect(await screen.findByLabelText('Slots in use')).toHaveAttribute(
      'value',
      '2'
    );
  });

  it('validates, asks to confirm, saves and shows the new limits', async () => {
    const user = userEvent.setup();
    panel();
    await user.click(
      await screen.findByRole('button', { name: 'Edit limits' })
    );
    const concurrent = screen.getByLabelText(/Max concurrent/);
    expect(concurrent).toHaveValue('4');

    await user.clear(concurrent);
    await user.type(concurrent, 'x');
    expect(
      screen.getByText('Enter whole numbers, 0 or more.')
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    await user.clear(concurrent);
    await user.type(concurrent, '8');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(
      screen.getByText(
        /Set max concurrent to 8 and max per workflow to Unlimited\?/
      )
    ).toBeInTheDocument();
    expect(posts).toEqual([]);

    // changing a value withdraws the confirmation
    await user.type(screen.getByLabelText(/Max per workflow/), '2');
    expect(screen.queryByText(/Set max concurrent/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await user.type(concurrent, '0');
    expect(screen.queryByText(/Set max concurrent/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await user.click(screen.getByRole('button', { name: 'Confirm and save' }));

    expect(await screen.findByText('80')).toBeInTheDocument();
    expect(posts).toEqual(['?maxConcurrent=80&maxPerWorkflow=2']);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('says an ADMIN key is needed on a 403, and shows other failures', async () => {
    const user = userEvent.setup();
    panel();
    await user.click(
      await screen.findByRole('button', { name: 'Edit limits' })
    );
    await user.click(screen.getByRole('button', { name: 'Save' }));

    postStatus = 403;
    await user.click(screen.getByRole('button', { name: 'Confirm and save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Changing limits needs an ADMIN key.'
    );

    postStatus = 409;
    await user.click(screen.getByRole('button', { name: 'Confirm and save' }));
    expect(await screen.findByText('refused')).toBeInTheDocument();
  });

  it('reports a failed read and retries', async () => {
    readFails = true;
    const user = userEvent.setup();
    panel();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to load the limits'
    );
    expect(
      screen.queryByRole('button', { name: 'Edit limits' })
    ).not.toBeInTheDocument();
    readFails = false;
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Queue capacity')).toBeInTheDocument();
  });

  it('refetches when a run starts or ends, but not for other events', async () => {
    panel();
    await screen.findByText('Queue capacity');
    const source = MockEventSource.byUrl('/api/events/stream');
    act(() => source.open());
    const before = reads;

    limits = { ...limits, running: 0 };
    act(() =>
      source.emit({
        type: 'WorkflowStatusChanged',
        severity: 'INFO',
        rootExecutionId: 'r',
        depth: 0,
        event: { executionId: 'e', timestamp: 't' },
      })
    );
    await waitFor(() =>
      expect(screen.getByLabelText('Slots in use')).toHaveAttribute(
        'value',
        '0'
      )
    );
    expect(reads).toBeGreaterThan(before);

    const after = reads;
    act(() =>
      source.emit({
        type: 'LogLine',
        severity: 'INFO',
        rootExecutionId: 'r',
        depth: 0,
        event: { executionId: 'e', timestamp: 't' },
      })
    );
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(reads).toBe(after);
  });
});
