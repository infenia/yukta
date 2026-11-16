// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { InboxPopover } from './InboxPopover';
import { useNotifications } from './NotificationsContext';
import { SEVERITY_DOT } from './severity';

/** The label a screen reader hears: how many questions wait, else how many alerts are unread. */
export function bellLabel(actions: number, unread: number): string {
  if (actions > 0) return `Notifications, ${actions} need action`;
  if (unread > 0) return `Notifications, ${unread} unread`;
  return 'Notifications';
}

/**
 * The bell in the header: the number of questions that wait for me, a dot when only unread alerts
 * exist, coloured by the highest severity, and muted while the live stream is down.
 */
export function NotificationBell() {
  const { actions, unread, highest, connected } = useNotifications();
  return (
    <Popover>
      <PopoverTrigger
        aria-label={bellLabel(actions.length, unread.length)}
        title={
          connected ? undefined : 'Live updates paused: checking every 30 s'
        }
        data-connected={connected}
        className={`text-on-surface-variant hover:bg-muted hover:text-on-surface relative mr-1 flex size-8 items-center justify-center rounded-full ${connected ? '' : 'opacity-60'}`}
      >
        <span aria-hidden="true" className="material-symbols-outlined text-lg">
          {connected ? 'notifications' : 'notifications_paused'}
        </span>
        {actions.length > 0 && highest && (
          <span
            data-testid="bell-count"
            aria-hidden="true"
            className={`text-label-sm absolute -top-0.5 -right-0.5 flex h-4.5 min-w-4.5 items-center justify-center rounded-full px-1 font-semibold text-white ${SEVERITY_DOT[highest]}`}
          >
            {actions.length}
          </span>
        )}
        {actions.length === 0 && unread.length > 0 && highest && (
          <span
            data-testid="bell-dot"
            aria-hidden="true"
            className={`absolute top-0.5 right-0.5 size-2 rounded-full ${SEVERITY_DOT[highest]}`}
          />
        )}
      </PopoverTrigger>
      <PopoverContent
        align="end"
        sideOffset={8}
        className="w-[min(25rem,calc(100vw-1rem))] p-0"
      >
        <InboxPopover />
      </PopoverContent>
    </Popover>
  );
}
