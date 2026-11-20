// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../api';
import { DesktopNotifier } from '../DesktopNotifier';
import {
  NotificationsContext,
  type NotificationsState,
} from '../NotificationsContext';
import { GENERIC_TITLE } from '../useDesktopNotifications';
import { action, note } from '../testFixtures';

vi.mock('../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api')>()),
  getPreferences: vi.fn(),
}));

const popups: Array<{
  title: string;
  options: NotificationOptions;
  onclick: (() => void) | null;
  close: () => void;
}> = [];

class FakeNotification {
  static permission: NotificationPermission = 'granted';
  onclick: (() => void) | null = null;
  close = vi.fn();
  constructor(
    public title: string,
    public options: NotificationOptions
  ) {
    popups.push(this);
  }
}

const state = (
  items: api.AppNotification[],
  loaded = true
): NotificationsState => ({
  items,
  actions: [],
  unread: [],
  highest: null,
  connected: true,
  toasts: [],
  loaded,
  dismissToast: vi.fn(),
  markRead: vi.fn(),
  dismiss: vi.fn(),
});

function Where() {
  return (
    <p data-testid="where">{useLocation().pathname + useLocation().search}</p>
  );
}

const tree = (s: NotificationsState, client: QueryClient) => (
  <QueryClientProvider client={client}>
    <MemoryRouter>
      <NotificationsContext.Provider value={s}>
        <DesktopNotifier />
        <Where />
      </NotificationsContext.Provider>
    </MemoryRouter>
  </QueryClientProvider>
);

const hidden = (value: 'hidden' | 'visible') =>
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(value);

/** Mounts with one item, then delivers a second one: only that one can be news. */
async function arrive(fresh: api.AppNotification) {
  const client = new QueryClient();
  const first = action({ id: 'old', category: 'input' });
  const view = render(tree(state([first]), client));
  await waitFor(() => expect(api.getPreferences).toHaveBeenCalled());
  await client.fetchQuery({
    queryKey: ['notification-preferences'],
    queryFn: api.getPreferences,
  });
  view.rerender(tree(state([fresh, first]), client));
}

beforeEach(() => {
  popups.length = 0;
  FakeNotification.permission = 'granted';
  vi.stubGlobal('Notification', FakeNotification);
  localStorage.setItem(
    'yukta.notifications.device',
    JSON.stringify({ desktop: true, details: true })
  );
  vi.mocked(api.getPreferences).mockResolvedValue({
    levels: { input: 'DESKTOP', 'run.failed': 'INBOX' },
    mutedSessions: [],
  });
  hidden('hidden');
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('desktop notifications', () => {
  it('raises one for a new question while the tab is hidden, with the title and workflow only', async () => {
    await arrive(
      action({ id: 'q', title: 'Deploy?', workflowId: 'release', input: null })
    );
    expect(popups).toHaveLength(1);
    expect(popups[0]?.title).toBe('Deploy?');
    expect(popups[0]?.options).toEqual({ body: 'release', tag: 'q' });
  });

  it('shows only the generic text when the device hides the details', async () => {
    localStorage.setItem(
      'yukta.notifications.device',
      JSON.stringify({ desktop: true, details: false })
    );
    await arrive(action({ id: 'q', title: 'Secret', workflowId: 'release' }));
    expect(popups[0]?.title).toBe(GENERIC_TITLE);
    expect(popups[0]?.options).toEqual({ tag: 'q' });
  });

  it('omits the body of an item with no workflow', async () => {
    await arrive(action({ id: 'q', title: 'T', workflowId: null }));
    expect(popups[0]?.options.body).toBeUndefined();
  });

  it('focuses the tab and opens the item on a click', async () => {
    const focus = vi.spyOn(window, 'focus').mockImplementation(() => undefined);
    await arrive(action({ id: 'q' }));
    popups[0]?.onclick?.();
    expect(focus).toHaveBeenCalled();
    expect(popups[0]?.close).toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.getByTestId('where')).toHaveTextContent(
        '/sessions/s1/workflow/wf?exec=e1&node=gate'
      )
    );
  });

  it.each([
    ['a visible tab', () => hidden('visible'), action({ id: 'q' })],
    [
      'a level below desktop',
      () => undefined,
      note({ id: 'q', category: 'run.failed' }),
    ],
    [
      'a category without a level',
      () => undefined,
      note({ id: 'q', category: 'workflow' }),
    ],
    ['a read item', () => undefined, action({ id: 'q', read: true })],
    ['an ended item', () => undefined, action({ id: 'q', state: 'RESOLVED' })],
    [
      'permission refused',
      () => {
        FakeNotification.permission = 'denied';
      },
      action({ id: 'q' }),
    ],
    [
      'the device switch off',
      () =>
        localStorage.setItem(
          'yukta.notifications.device',
          JSON.stringify({ desktop: false, details: true })
        ),
      action({ id: 'q' }),
    ],
    [
      'no browser support',
      () => vi.stubGlobal('Notification', undefined),
      action({ id: 'q' }),
    ],
  ])('raises nothing for %s', async (_name, setup, item) => {
    setup();
    await arrive(item);
    expect(popups).toHaveLength(0);
  });

  it('does not announce what was there at load, an item twice, or anything before the list is in', async () => {
    const client = new QueryClient();
    const q = action({ id: 'q' });
    const view = render(tree(state([q], false), client));
    view.rerender(tree(state([q], true), client));
    view.rerender(tree(state([q, action({ id: 'again' })], true), client));
    view.rerender(tree(state([q, action({ id: 'again' })], true), client));
    expect(popups.filter((p) => p.options.tag === 'q')).toHaveLength(0);
    expect(
      popups.filter((p) => p.options.tag === 'again').length
    ).toBeLessThanOrEqual(1);
  });
});
