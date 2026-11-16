// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { act, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  installMockEventSource,
  MockEventSource,
} from '@/test/mocks/MockEventSource';
import * as api from '../api';
import { NotificationsProvider, POLL_MS } from '../NotificationsProvider';
import { useNotifications } from '../NotificationsContext';
import { action, note } from '../testFixtures';

vi.mock('../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api')>()),
  listNotifications: vi.fn(),
  markNotificationsRead: vi.fn(),
  dismissNotification: vi.fn(),
}));

function Probe() {
  const s = useNotifications();
  return (
    <div>
      <p data-testid="counts">
        {s.actions.length}/{s.unread.length}/{s.highest ?? 'none'}/
        {String(s.connected)}/{s.items.length}/{s.toasts.length}
      </p>
      <button onClick={() => s.markRead(['a'])}>read-a</button>
      <button onClick={() => s.markRead()}>read-all</button>
      <button onClick={() => s.dismiss('x')}>drop-x</button>
      <p data-testid="loaded">{String(s.loaded)}</p>
      <button onClick={() => s.dismissToast(s.toasts[0]?.id ?? '')}>
        dismiss
      </button>
    </div>
  );
}

const counts = () => screen.getByTestId('counts').textContent;
const open = () => act(() => MockEventSource.latest().open());
const emit = (event: Partial<api.NotificationEvent>) =>
  act(() =>
    MockEventSource.latest().emit({
      type: 'created',
      notification: null,
      notificationId: null,
      readAt: null,
      dismissedAt: null,
      ...event,
    })
  );

describe('NotificationsProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    installMockEventSource();
    document.title = 'Yukta | Sessions';
    vi.mocked(api.listNotifications).mockResolvedValue([]);
    vi.mocked(api.markNotificationsRead).mockResolvedValue({ marked: 1 });
    vi.mocked(api.dismissNotification).mockResolvedValue(null);
  });
  afterEach(() => vi.useRealTimers());

  const mount = () =>
    render(
      <QueryClientProvider client={new QueryClient()}>
        <NotificationsProvider>
          <Probe />
        </NotificationsProvider>
      </QueryClientProvider>
    );

  it('loads the list and counts actions, unread info and the highest severity', async () => {
    vi.mocked(api.listNotifications).mockResolvedValue([
      note({ id: 'i', severity: 'ERROR' }),
      action({ id: 'q' }),
      note({ id: 'read', read: true }),
    ]);
    mount();
    open();

    await waitFor(() => expect(counts()).toBe('1/1/ERROR/true/3/0'));
  });

  it('applies created, updated, resolved and receipt events', async () => {
    mount();
    open();
    emit({ type: 'created', notification: action({ id: 'q', seq: 10 }) });
    expect(counts()).toBe('1/0/WARN/true/1/1');

    emit({
      type: 'updated',
      notification: action({ id: 'q', seq: 11, title: 'Deploy now?' }),
    });
    expect(counts()).toBe('1/0/WARN/true/1/1');

    emit({ type: 'created', notification: note({ id: 'i', seq: 12 }) });
    expect(counts()).toBe('1/1/WARN/true/2/2');
    emit({ type: 'receipt', notificationId: 'i', readAt: 't' });
    expect(counts()).toBe('1/0/WARN/true/2/2');

    emit({
      type: 'resolved',
      notification: action({
        id: 'q',
        seq: 13,
        state: 'RESOLVED',
        resolution: 'answered by key:ann: approve',
      }),
    });
    expect(counts()).toBe('0/0/none/true/2/2');
    emit({ type: 'receipt', notificationId: 'i', dismissedAt: 't' });
    expect(counts()).toBe('0/0/none/true/1/2');
    emit({ type: 'created', notification: null });
    expect(counts()).toBe('0/0/none/true/1/2');
    emit({
      type: 'updated',
      notification: note({ id: 'q', seq: 20, dismissed: true }),
    });
    expect(counts()).toBe('0/0/none/true/0/2');
  });

  it('shows a toast once per item, not for a replay or a hidden tab or a read item', async () => {
    mount();
    open();
    emit({ type: 'created', notification: note({ id: 'a', seq: 1 }) });
    emit({ type: 'created', notification: note({ id: 'a', seq: 1 }) });
    emit({
      type: 'created',
      notification: note({ id: 'r', seq: 2, read: true }),
    });
    expect(counts()).toBe('0/1/INFO/true/2/1');

    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    emit({ type: 'created', notification: note({ id: 'h', seq: 3 }) });
    expect(counts()).toBe('0/2/INFO/true/3/1');

    act(() => screen.getByText('dismiss').click());
    expect(counts()).toBe('0/2/INFO/true/3/0');
  });

  it('keeps an open toast up to date when its question is answered elsewhere', async () => {
    const Toasts = () => {
      const { toasts } = useNotifications();
      return (
        <p data-testid="t">
          {toasts.map((t) => t.notification.state).join(',')}
        </p>
      );
    };
    render(
      <QueryClientProvider client={new QueryClient()}>
        <NotificationsProvider>
          <Toasts />
        </NotificationsProvider>
      </QueryClientProvider>
    );
    open();
    emit({ type: 'created', notification: action({ id: 'q', seq: 1 }) });
    emit({ type: 'created', notification: note({ id: 'o', seq: 2 }) });
    emit({
      type: 'resolved',
      notification: action({ id: 'q', seq: 3, state: 'RESOLVED' }),
    });

    expect(screen.getByTestId('t').textContent).toBe('ACTIVE,RESOLVED');
  });

  it('dismisses an item at once and reads the list again if the server refuses', async () => {
    vi.mocked(api.listNotifications).mockResolvedValue([note({ id: 'x' })]);
    mount();
    open();
    await waitFor(() => expect(counts()).toBe('0/1/INFO/true/1/0'));
    expect(screen.getByTestId('loaded').textContent).toBe('true');

    act(() => screen.getByText('drop-x').click());
    expect(counts()).toBe('0/0/none/true/0/0');
    expect(api.dismissNotification).toHaveBeenCalledWith('x');

    vi.mocked(api.dismissNotification).mockRejectedValue(new Error('no'));
    const reads = vi.mocked(api.listNotifications).mock.calls.length;
    act(() => screen.getByText('drop-x').click());
    await waitFor(() =>
      expect(
        vi.mocked(api.listNotifications).mock.calls.length
      ).toBeGreaterThan(reads)
    );
  });

  it('reads the list again on a reset', async () => {
    mount();
    open();
    await waitFor(() => expect(api.listNotifications).toHaveBeenCalledTimes(1));
    vi.mocked(api.listNotifications).mockResolvedValue([note({ id: 'x' })]);

    emit({ type: 'reset' });

    await waitFor(() => expect(counts()).toBe('0/1/INFO/true/1/0'));
  });

  it('polls every 30 s while the stream is down and stops once it is up', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mount();
    await vi.advanceTimersByTimeAsync(POLL_MS);
    expect(api.listNotifications).toHaveBeenCalledTimes(2);
    expect(counts()).toContain('/false/');

    open();
    await vi.advanceTimersByTimeAsync(POLL_MS * 2);

    expect(api.listNotifications).toHaveBeenCalledTimes(2);
    expect(counts()).toContain('/true/');
  });

  it('keeps what it has when the list cannot be read', async () => {
    vi.mocked(api.listNotifications).mockRejectedValue(new Error('down'));
    mount();
    open();
    await waitFor(() => expect(api.listNotifications).toHaveBeenCalled());
    expect(counts()).toBe('0/0/none/true/0/0');
  });

  it('marks read at once and asks the server; a failure reads the list again', async () => {
    vi.mocked(api.listNotifications).mockResolvedValue([
      note({ id: 'a' }),
      note({ id: 'b' }),
    ]);
    mount();
    open();
    await waitFor(() => expect(counts()).toBe('0/2/INFO/true/2/0'));

    act(() => screen.getByText('read-a').click());
    expect(counts()).toBe('0/1/INFO/true/2/0');
    expect(api.markNotificationsRead).toHaveBeenCalledWith(['a']);

    vi.mocked(api.markNotificationsRead).mockRejectedValue(new Error('x'));
    act(() => screen.getByText('read-all').click());
    expect(counts()).toBe('0/0/none/true/2/0');
    await waitFor(() => expect(api.listNotifications).toHaveBeenCalledTimes(2));
  });

  it('puts the number of waiting questions in front of the page title and takes it off again', async () => {
    const { unmount } = mount();
    open();
    emit({ type: 'created', notification: action({ id: 'q', seq: 1 }) });
    expect(document.title).toBe('(1) Yukta | Sessions');

    await act(async () => {
      document.title = 'Yukta | Runs';
    });
    await waitFor(() => expect(document.title).toBe('(1) Yukta | Runs'));

    emit({
      type: 'resolved',
      notification: action({ id: 'q', seq: 2, state: 'RESOLVED' }),
    });
    await waitFor(() => expect(document.title).toBe('Yukta | Runs'));
    emit({ type: 'created', notification: action({ id: 'q2', seq: 3 }) });
    unmount();
    expect(document.title).toBe('Yukta | Runs');
  });
});
