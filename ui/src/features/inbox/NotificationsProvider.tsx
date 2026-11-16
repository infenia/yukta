// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQueryClient } from '@tanstack/react-query';
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import {
  dismissNotification,
  listNotifications,
  markNotificationsRead,
  NOTIFICATIONS_STREAM,
  type AppNotification,
  type NotificationEvent,
  type NotificationSeverity,
} from './api';
import {
  NotificationsContext,
  type NotificationsState,
  type Toast,
} from './NotificationsContext';

/** How often the list is read while the stream is down. */
export const POLL_MS = 30_000;
const RANK: Record<NotificationSeverity, number> = {
  INFO: 0,
  WARN: 1,
  ERROR: 2,
};

/** Newest change first. */
const byRecent = (a: AppNotification, b: AppNotification) => b.seq - a.seq;

/** Puts an item in the list, or replaces the one with its id. */
function upsert(
  items: AppNotification[],
  item: AppNotification
): AppNotification[] {
  if (item.dismissed) return items.filter((i) => i.id !== item.id);
  return [item, ...items.filter((i) => i.id !== item.id)].sort(byRecent);
}

/**
 * The list the server answered, plus what the stream delivered after that list was made: an item
 * that is newer than anything in the answer (an event can overtake the answer) is kept; one that
 * is not in the answer and not newer is gone (dismissed or purged).
 */
function merge(
  list: AppNotification[],
  current: AppNotification[]
): AppNotification[] {
  const newest = list.reduce((top, i) => Math.max(top, i.seq), -1);
  const ids = new Set(list.map((i) => i.id));
  const kept = current.filter((i) => !ids.has(i.id) && i.seq > newest);
  return [...kept, ...list].sort(byRecent);
}

/** Keeps the document title as "(2) Yukta": the count of actions that wait. */
function useTitleCount(count: number) {
  useEffect(() => {
    const apply = () => {
      const plain = document.title.replace(/^\(\d+\) /, '');
      const wanted = count > 0 ? `(${count}) ${plain}` : plain;
      if (document.title !== wanted) document.title = wanted;
    };
    apply();
    // a page sets its own title when it opens: put the count back in front of it
    const observer = new MutationObserver(apply);
    // the whole head: a page may change the title's text or replace the element
    observer.observe(document.head, {
      childList: true,
      characterData: true,
      subtree: true,
    });
    return () => {
      observer.disconnect();
      document.title = document.title.replace(/^\(\d+\) /, '');
    };
  }, [count]);
}

/**
 * One notification stream for the whole app: the list, the counts for the bell and the toasts for
 * what arrives. While the stream is down the list is read every 30 s. A reset (the server cannot
 * replay what was missed) reads the list again.
 */
export function NotificationsProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [loaded, setLoaded] = useState(false);
  const [items, setItems] = useState<AppNotification[]>([]);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const seen = useRef(new Set<string>());

  const load = useCallback(() => {
    listNotifications()
      .then((list) => setItems((current) => merge(list, current)))
      .catch(() => undefined) // the bell stays as it was; the next try may work
      .finally(() => setLoaded(true));
  }, []);

  const onEvent = useCallback(
    (event: NotificationEvent) => {
      // the panels of a run that show its questions read them from the inputs queries
      void queryClient.invalidateQueries({ queryKey: ['inputs'] });
      if (event.type === 'reset') {
        load();
        return;
      }
      if (event.type === 'receipt') {
        setItems((current) =>
          current
            .map((i) =>
              i.id === event.notificationId ? { ...i, read: true } : i
            )
            .filter(
              (i) => !(i.id === event.notificationId && event.dismissedAt)
            )
        );
        return;
      }
      const item = event.notification;
      if (!item) return;
      setItems((current) => upsert(current, item));
      const fresh = event.type === 'created' && !seen.current.has(item.id);
      seen.current.add(item.id);
      if (fresh && !item.read && document.visibilityState === 'visible') {
        setToasts((current) => [
          { id: item.id, notification: item },
          ...current,
        ]);
      }
      if (event.type === 'resolved') {
        // answered elsewhere while a toast is open: the toast shows how it ended
        setToasts((current) =>
          current.map((t) =>
            t.id === item.id ? { ...t, notification: item } : t
          )
        );
      }
    },
    [load, queryClient]
  );

  const { status } = useEventSourceJson<NotificationEvent>(
    NOTIFICATIONS_STREAM,
    onEvent
  );
  const connected = status === 'open';

  useEffect(() => {
    load();
  }, [load]);
  useEffect(() => {
    if (connected) return undefined;
    const timer = setInterval(load, POLL_MS);
    return () => clearInterval(timer);
  }, [connected, load]);

  const dismissToast = useCallback(
    (id: string) => setToasts((current) => current.filter((t) => t.id !== id)),
    []
  );
  const markRead = useCallback(
    (ids?: string[]) => {
      setItems((current) =>
        current.map((i) =>
          !ids || ids.includes(i.id) ? { ...i, read: true } : i
        )
      );
      markNotificationsRead(ids).catch(load);
    },
    [load]
  );

  const dismiss = useCallback(
    (id: string) => {
      setItems((current) => current.filter((i) => i.id !== id));
      dismissNotification(id).catch(load);
    },
    [load]
  );

  const state = useMemo<NotificationsState>(() => {
    const actions = items.filter(
      (i) => i.kind === 'ACTION' && i.state === 'ACTIVE'
    );
    const unread = items.filter((i) => i.kind === 'INFO' && !i.read);
    const highest = [...actions, ...unread].reduce<NotificationSeverity | null>(
      (top, i) =>
        top === null || RANK[i.severity] > RANK[top] ? i.severity : top,
      null
    );
    return {
      items,
      actions,
      unread,
      highest,
      connected,
      toasts,
      loaded,
      dismissToast,
      markRead,
      dismiss,
    };
  }, [items, connected, toasts, loaded, dismissToast, markRead, dismiss]);

  useTitleCount(state.actions.length);

  return (
    <NotificationsContext.Provider value={state}>
      {children}
    </NotificationsContext.Provider>
  );
}
