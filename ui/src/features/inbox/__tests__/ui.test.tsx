// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as inputsApi from '@/features/workflow/api/inputs';
import { INFO_TOAST_MS, Toaster } from '../Toaster';
import { bellLabel, NotificationBell } from '../NotificationBell';
import { byDeadline, InboxPopover } from '../InboxPopover';
import {
  NotificationsContext,
  type NotificationsState,
} from '../NotificationsContext';
import { plain } from '../severity';
import type { AppNotification } from '../api';
import { must } from '@/test/utils/testUtils';
import { action, input, note } from '../testFixtures';

vi.mock('@/features/workflow/api/inputs', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/workflow/api/inputs')>()),
  answerInput: vi.fn(),
}));

const state = (
  items: AppNotification[],
  over: Partial<NotificationsState> = {}
): NotificationsState => {
  const actions = items.filter(
    (i) => i.kind === 'ACTION' && i.state === 'ACTIVE'
  );
  const unread = items.filter((i) => i.kind === 'INFO' && !i.read);
  const rank = { INFO: 0, WARN: 1, ERROR: 2 } as const;
  const highest = [...actions, ...unread].reduce<
    AppNotification['severity'] | null
  >(
    (top, i) =>
      top === null || rank[i.severity] > rank[top] ? i.severity : top,
    null
  );
  return {
    items,
    actions,
    unread,
    highest,
    connected: true,
    toasts: [],
    loaded: true,
    dismissToast: vi.fn(),
    markRead: vi.fn(),
    dismiss: vi.fn(),
    ...over,
  };
};

const view = (value: NotificationsState, ui: React.ReactNode) =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <NotificationsContext.Provider value={value}>
          {ui}
        </NotificationsContext.Provider>
      </MemoryRouter>
    </QueryClientProvider>
  );

beforeEach(() => {
  vi.mocked(inputsApi.answerInput).mockResolvedValue(input());
});
afterEach(() => vi.useRealTimers());

describe('severity', () => {
  it('shows text without control characters', () => {
    expect(plain('a\x1b[31mb\nc')).toBe('a [31mb c');
  });
});

describe('NotificationBell', () => {
  it('says how many questions wait, coloured by the highest severity', () => {
    view(
      state([action({ severity: 'ERROR' }), action(), note()]),
      <NotificationBell />
    );

    const bell = screen.getByRole('button', {
      name: 'Notifications, 2 need action',
    });
    expect(within(bell).getByTestId('bell-count')).toHaveTextContent('2');
    expect(within(bell).getByTestId('bell-count').className).toContain(
      'bg-destructive'
    );
  });

  it('shows a dot when only unread alerts exist, and nothing when all is read', () => {
    const { unmount } = view(
      state([note({ severity: 'WARN' })]),
      <NotificationBell />
    );
    const bell = screen.getByRole('button', {
      name: 'Notifications, 1 unread',
    });
    expect(within(bell).getByTestId('bell-dot').className).toContain(
      'bg-warning'
    );
    unmount();

    view(state([note({ read: true })]), <NotificationBell />);
    const quiet = screen.getByRole('button', { name: 'Notifications' });
    expect(within(quiet).queryByTestId('bell-dot')).toBeNull();
    expect(within(quiet).queryByTestId('bell-count')).toBeNull();
  });

  it('is muted and says so while the live stream is down', () => {
    view(state([], { connected: false }), <NotificationBell />);

    const bell = screen.getByRole('button', { name: 'Notifications' });
    expect(bell).toHaveAttribute('data-connected', 'false');
    expect(bell).toHaveAttribute('title', expect.stringContaining('paused'));
    expect(bellLabel(0, 0)).toBe('Notifications');
  });

  it('opens the popover with the keyboard', async () => {
    view(state([action({ title: 'Ship it?' })]), <NotificationBell />);

    screen.getByRole('button', { name: /Notifications/ }).focus();
    await userEvent.keyboard('{Enter}');

    expect(await screen.findByText('Needs action · 1')).toBeInTheDocument();
    expect(screen.getByText('Ship it?')).toBeInTheDocument();
  });
});

describe('InboxPopover', () => {
  it('lists the questions by deadline, answers an approval inline and offers the inbox', async () => {
    const soon = action({
      id: 'soon',
      title: 'Soon',
      input: input({ deadline: '2026-11-15T10:05:00Z' }),
    });
    const later = action({
      id: 'later',
      title: 'Later',
      input: input({ id: 'r2', deadline: '2026-11-15T12:00:00Z' }),
    });
    const none = action({
      id: 'none',
      title: 'No deadline',
      input: input({ id: 'r3', risk: 'HIGH' }),
    });
    view(state([none, later, soon]), <InboxPopover />);

    const titles = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(titles[0]).toContain('Soon');
    expect(titles[2]).toContain('No deadline');
    expect(screen.getByText('high risk')).toBeInTheDocument();
    await userEvent.click(
      within(must(screen.getAllByRole('listitem')[0])).getByRole('button', {
        name: 'Approve',
      })
    );

    await waitFor(() =>
      expect(inputsApi.answerInput).toHaveBeenCalledWith('e1', 'r1', {
        decision: 'approve',
        expectedVersion: 3,
      })
    );
    expect(screen.getByRole('link', { name: 'Open inbox' })).toHaveAttribute(
      'href',
      '/inbox'
    );
  });

  it('sends a text or structured question to the inbox instead of answering inline', () => {
    const text = action({ title: 'Why?', input: input({ kind: 'TEXT' }) });
    const noInput = action({
      id: 'x',
      title: 'Odd',
      input: null,
      severity: 'ERROR',
    });
    view(state([text, noInput]), <InboxPopover />);

    const links = screen.getAllByRole('link', { name: 'Answer…' });
    expect(links[0]).toHaveAttribute('href', `/inbox?item=${text.id}`);
    expect(screen.getByText('Error')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
  });

  it('shows at most five questions and five alerts, and marks all read', async () => {
    const markRead = vi.fn();
    const questions = Array.from({ length: 7 }, (_, i) =>
      action({
        id: `q${i}`,
        title: `Q${i}`,
        input: input({ id: `r${i}`, kind: 'TEXT' }),
      })
    );
    const alerts = Array.from({ length: 7 }, (_, i) =>
      note({ id: `a${i}`, title: `A${i}`, read: i === 0 })
    );
    view(state([...questions, ...alerts], { markRead }), <InboxPopover />);

    expect(screen.getAllByRole('link', { name: 'Answer…' })).toHaveLength(5);
    expect(screen.getByText('Needs action · 7')).toBeInTheDocument();
    expect(screen.getAllByText(/unread/)).toHaveLength(4);
    await userEvent.click(
      screen.getByRole('button', { name: 'Mark all read' })
    );
    expect(markRead).toHaveBeenCalledWith();
  });

  it('says so when nothing waits and nothing happened, and cannot mark read', () => {
    view(state([]), <InboxPopover />);

    expect(screen.getByText('Nothing waits for you.')).toBeInTheDocument();
    expect(screen.getByText('No alerts.')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Mark all read' })
    ).toBeDisabled();
  });

  it('keeps a question that someone else answered while it is open, saying who', () => {
    const q = action({ id: 'q', title: 'Deploy?' });
    const { rerender } = view(state([q]), <InboxPopover />);
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();

    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <NotificationsContext.Provider
            value={state([
              {
                ...q,
                state: 'RESOLVED',
                resolution: 'answered by key:ann: approve',
              },
            ])}
          >
            <InboxPopover />
          </NotificationsContext.Provider>
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(screen.getByRole('status')).toHaveTextContent(
      'answered by key:ann: approve'
    );
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
  });

  it('shows Done for a resolved question without a resolution text', () => {
    const q = action({ id: 'q' });
    const { rerender } = view(state([q]), <InboxPopover />);
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <NotificationsContext.Provider
            value={state([{ ...q, state: 'RESOLVED' }])}
          >
            <InboxPopover />
          </NotificationsContext.Provider>
        </MemoryRouter>
      </QueryClientProvider>
    );
    expect(screen.getByRole('status')).toHaveTextContent('Done');
  });

  it('orders by deadline then by age', () => {
    const a = action({ createdAt: '2026-11-15T09:00:00Z' });
    const b = action({ createdAt: '2026-11-15T10:00:00Z' });
    expect(byDeadline(a, b)).toBeLessThan(0);
  });
});

describe('Toaster', () => {
  const toast = (n: AppNotification) => ({ id: n.id, notification: n });

  it('renders nothing without toasts', () => {
    const { container } = view(state([]), <Toaster />);
    expect(container).toBeEmptyDOMElement();
  });

  it('announces an error assertively and an alert politely, and dismisses an alert after 8 s', () => {
    vi.useFakeTimers();
    const dismissToast = vi.fn();
    const err = note({ id: 'e', severity: 'ERROR', title: 'Boom' });
    const info = note({ id: 'i', title: 'FYI' });
    view(
      state([err, info], { toasts: [toast(err), toast(info)], dismissToast }),
      <Toaster />
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Boom');
    expect(screen.getByRole('status')).toHaveTextContent('FYI');
    act(() => vi.advanceTimersByTime(INFO_TOAST_MS));
    expect(dismissToast).toHaveBeenCalledWith('e');
    expect(dismissToast).toHaveBeenCalledWith('i');
  });

  it('keeps a question until it is dismissed, and answers an approval from the toast', async () => {
    const dismissToast = vi.fn();
    const q = action({ id: 'q', title: 'Ship?' });
    view(state([q], { toasts: [toast(q)], dismissToast }), <Toaster />);

    expect(screen.getByText('low risk')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(dismissToast).toHaveBeenCalledWith('q'));
    expect(inputsApi.answerInput).toHaveBeenCalledWith(
      'e1',
      'r1',
      expect.objectContaining({ decision: 'reject' })
    );
  });

  it('shows how an answered question ended and then goes away', () => {
    vi.useFakeTimers();
    const dismissToast = vi.fn();
    const q = action({
      id: 'q',
      state: 'RESOLVED',
      resolution: 'answered by key:ann: approve',
    });
    view(state([q], { toasts: [toast(q)], dismissToast }), <Toaster />);

    expect(
      screen.getByText('answered by key:ann: approve')
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
    act(() => vi.advanceTimersByTime(INFO_TOAST_MS));
    expect(dismissToast).toHaveBeenCalledWith('q');
  });

  it('links to the item, dismisses by button, and shows at most three', async () => {
    const dismissToast = vi.fn();
    const list = Array.from({ length: 5 }, (_, i) =>
      note({ id: `n${i}`, title: `T${i}` })
    );
    view(state(list, { toasts: list.map(toast), dismissToast }), <Toaster />);

    expect(screen.getAllByTestId('toast')).toHaveLength(3);
    expect(screen.getAllByRole('link', { name: 'View' })[0]).toHaveAttribute(
      'href',
      '/inbox?item=n0'
    );
    fireEvent.click(
      must(screen.getAllByRole('button', { name: 'Dismiss' })[0])
    );
    expect(dismissToast).toHaveBeenCalledWith('n0');
    fireEvent.click(must(screen.getAllByRole('link', { name: 'View' })[1]));
    expect(dismissToast).toHaveBeenCalledWith('n1');
  });
});
