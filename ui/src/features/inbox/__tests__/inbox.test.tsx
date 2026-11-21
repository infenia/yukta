// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { choose } from '@/test/utils/choose';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as inputsApi from '@/features/workflow/api/inputs';
import { InboxPage } from '../InboxPage';
import {
  NotificationsContext,
  type NotificationsState,
} from '../NotificationsContext';
import type { AppNotification } from '../api';
import { action, input, note } from '../testFixtures';

vi.mock('@/features/workflow/api/inputs', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/workflow/api/inputs')>()),
  answerInput: vi.fn(),
}));

const markRead = vi.fn();
const dismiss = vi.fn();

const state = (
  items: AppNotification[],
  over: Partial<NotificationsState> = {}
): NotificationsState => ({
  items,
  actions: items.filter((i) => i.kind === 'ACTION' && i.state === 'ACTIVE'),
  unread: items.filter((i) => i.kind === 'INFO' && !i.read),
  highest: null,
  connected: true,
  toasts: [],
  loaded: true,
  dismissToast: vi.fn(),
  markRead,
  dismiss,
  ...over,
});

function Where() {
  const { search } = useLocation();
  return <p data-testid="where">{search}</p>;
}

const open = (value: NotificationsState, url = '/inbox') =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <NotificationsContext.Provider value={value}>
          <InboxPage />
          <Where />
        </NotificationsContext.Provider>
      </MemoryRouter>
    </QueryClientProvider>
  );

const fixtures = () => ({
  approval: action({
    id: 'appr',
    title: 'Deploy?',
    sessionId: 's1',
    input: input({
      deadline: new Date(Date.now() + 120_000).toISOString(),
      asked: [
        { who: 'guard', said: 'asked for approval', reason: null, at: 't' },
      ],
    }),
  }),
  text: action({
    id: 'text',
    title: 'Why?',
    sessionId: 's2',
    input: input({ id: 'r2', kind: 'TEXT', prompt: 'Explain the rollback' }),
  }),
  info: note({
    id: 'info',
    title: 'Run failed',
    severity: 'ERROR',
    body: 'node export timed out',
    category: 'run.failed',
  }),
  done: action({
    id: 'done',
    title: 'Old question',
    state: 'RESOLVED',
    resolution: 'answered by key:ann: approve',
    sessionId: 's1',
  }),
  bare: note({
    id: 'bare',
    title: 'Plain',
    read: true,
    sessionId: null,
    state: 'RESOLVED',
  }),
});

beforeEach(() => {
  markRead.mockClear();
  dismiss.mockClear();
  vi.mocked(inputsApi.answerInput).mockResolvedValue(input());
});

describe('InboxPage', () => {
  it('shows the three tabs with counts and the questions that wait first', async () => {
    const f = fixtures();
    open(state([f.approval, f.text, f.info, f.done]));

    expect(screen.getByRole('tab', { name: /Needs action/ })).toHaveTextContent(
      '2'
    );
    expect(screen.getByRole('tab', { name: /All/ })).toHaveTextContent('4');
    expect(screen.getByRole('tab', { name: /Resolved/ })).toHaveTextContent(
      '1'
    );
    expect(screen.getByText('Deploy?')).toBeInTheDocument();
    expect(screen.queryByText('Run failed')).toBeNull();
    expect(screen.getByLabelText('Approval chain')).toBeInTheDocument();
    expect(screen.getByText(/tier operator · /)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: /All/ }));
    expect(screen.getByText('Run failed')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: /Resolved/ }));
    expect(screen.getByRole('status')).toHaveTextContent(
      'answered by key:ann: approve'
    );
  });

  it('filters by severity, session and category', async () => {
    const f = fixtures();
    open(state([f.approval, f.text, f.info, f.done, f.bare]));
    await userEvent.click(screen.getByRole('tab', { name: /All/ }));

    await choose(userEvent, screen.getByLabelText('Severity'), 'ERROR');
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    await choose(userEvent, screen.getByLabelText('Severity'), '');
    await choose(userEvent, screen.getByLabelText('Severity'), 'WARN');
    expect(screen.getAllByRole('listitem')).toHaveLength(4);
    await choose(userEvent, screen.getByLabelText('Severity'), '');
    await choose(userEvent, screen.getByLabelText('Session'), 's2');
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    await choose(userEvent, screen.getByLabelText('Session'), '');
    await choose(userEvent, screen.getByLabelText('Category'), 'run.failed');
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    await userEvent.click(screen.getByLabelText('Category'));
    expect((await screen.findAllByRole('option')).length).toBeGreaterThan(2);
  });

  it('answers an approval in place and opens a sheet for a text question', async () => {
    const f = fixtures();
    open(state([f.approval, f.text]));

    await userEvent.click(
      screen.getAllByRole('button', { name: 'Approve' })[0] as HTMLElement
    );
    await waitFor(() => expect(inputsApi.answerInput).toHaveBeenCalled());

    await userEvent.click(screen.getByRole('button', { name: 'Answer…' }));
    const sheet = await screen.findByRole('dialog');
    expect(within(sheet).getByText('Explain the rollback')).toBeInTheDocument();
    await userEvent.type(within(sheet).getByLabelText('Answer'), 'because');
    await userEvent.click(
      within(sheet).getByRole('button', { name: 'Send answer' })
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(inputsApi.answerInput).toHaveBeenLastCalledWith(
      'e1',
      'r2',
      expect.objectContaining({ text: 'because' })
    );
  });

  it('marks one read, marks all read, and dismisses an alert but not an open question', async () => {
    const f = fixtures();
    open(state([f.approval, f.info, f.done]));
    expect(screen.queryAllByRole('button', { name: 'Dismiss' })).toHaveLength(
      0
    );
    await userEvent.click(screen.getByRole('tab', { name: /All/ }));

    expect(screen.getAllByRole('button', { name: 'Dismiss' })).toHaveLength(2);
    await userEvent.click(
      screen.getAllByRole('button', { name: 'Mark read' })[1] as HTMLElement
    );
    expect(markRead).toHaveBeenCalledWith(['info']);
    await userEvent.click(
      screen.getByRole('button', { name: 'Mark all read' })
    );
    expect(markRead).toHaveBeenCalledWith();
    await userEvent.click(
      screen.getAllByRole('button', { name: 'Dismiss' })[0] as HTMLElement
    );
    expect(dismiss).toHaveBeenCalled();
  });

  it('says what is missing: loading, empty tabs, a paused stream', async () => {
    const { unmount } = open(state([], { loaded: false }));
    expect(screen.getByRole('status')).toHaveTextContent('Loading…');
    unmount();

    open(state([], { connected: false }));
    expect(screen.getByText("You're all caught up")).toBeInTheDocument();
    expect(screen.getByText(/refreshed every 30 s/)).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Mark all read' })
    ).toBeDisabled();
    await userEvent.click(screen.getByRole('tab', { name: /All/ }));
    expect(screen.getByText('Nothing here')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: /Resolved/ }));
    expect(screen.getByText('Nothing has ended yet')).toBeInTheDocument();
  });

  it('follows a deep link to a question, marks its row and shows its tab', () => {
    const f = fixtures();
    open(state([f.approval, f.info, f.done, f.bare]), '/inbox?item=appr');

    expect(document.getElementById('item-appr')).toHaveAttribute(
      'aria-current',
      'true'
    );
    expect(screen.getByRole('tab', { name: /Needs action/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
  });

  it('follows a deep link to an alert, to a resolved item and to a text question', async () => {
    const f = fixtures();
    const { unmount } = open(state([f.approval, f.info]), '/inbox?item=info');
    expect(screen.getByRole('tab', { name: /All/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    unmount();

    const second = open(state([f.approval, f.done]), '/inbox?item=done');
    expect(screen.getByRole('tab', { name: /Resolved/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    second.unmount();

    open(state([f.text]), '/inbox?item=text');
    const sheet = await screen.findByRole('dialog');
    expect(within(sheet).getByText('Explain the rollback')).toBeInTheDocument();
    await userEvent.click(within(sheet).getByRole('button', { name: 'Close' }));
    await waitFor(() =>
      expect(screen.getByTestId('where')).toHaveTextContent(/^$/)
    );
  });

  it('a deep link is never hidden by a filter, and an unknown one says so', async () => {
    const f = fixtures();
    const { unmount } = open(state([f.approval, f.info]), '/inbox?item=info');
    await choose(userEvent, screen.getByLabelText('Session'), '');
    expect(document.getElementById('item-info')).not.toBeNull();
    unmount();

    open(state([f.approval]), '/inbox?item=gone');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'not available to you'
    );
  });

  it('does not say an item is missing while the list is loading', () => {
    open(state([], { loaded: false }), '/inbox?item=x');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('links a row to its run and node, and marks only unread rows read when followed', async () => {
    const f = fixtures();
    const placed = note({
      id: 'placed',
      title: 'Placed',
      executionId: 'e9',
      nodeId: 'gate',
      read: true,
    });
    const loose = note({
      id: 'loose',
      title: 'Loose',
      sessionId: null,
      workflowId: null,
      nodeId: null,
      read: true,
    });
    open(state([f.info, f.bare, placed, loose]));
    await userEvent.click(screen.getByRole('tab', { name: /All/ }));
    expect(screen.getByText('Loose')).toBeInTheDocument();

    const row = document.getElementById('item-placed');
    expect(row).not.toBeNull();
    expect(
      within(row as HTMLElement).getByRole('link', { name: 'gate' })
    ).toHaveAttribute('href', '/sessions/s1/workflow/wf?exec=e9&node=gate');

    await userEvent.click(
      within(document.getElementById('item-info') as HTMLElement).getAllByRole(
        'link'
      )[0] as HTMLElement
    );
    expect(markRead).toHaveBeenCalledWith(['info']);
    markRead.mockClear();

    await userEvent.click(
      within(row as HTMLElement).getAllByRole('link')[0] as HTMLElement
    );
    expect(markRead).not.toHaveBeenCalled();
  });
});
