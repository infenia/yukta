// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link } from 'react-router';
import { IconButton } from '@/components/common/IconButton';
import { iconButton } from '@/components/ui/iconTone';
import { Tip } from '@/components/ui/tooltip';
import { InputAnswerForm } from '@/features/workflow/components/InputAnswerForm';
import { formatRelative } from '@/features/workflow/lib/timeFormat';
import type { AppNotification } from './api';
import { NotificationLink } from './NotificationLink';
import { useNotifications } from './NotificationsContext';
import { plain, SEVERITY_DOT, SEVERITY_LABEL } from './severity';

const MAX_ACTIONS = 5;
const MAX_RECENT = 5;

/** The question that is due first comes first; one without a deadline goes after. */
export const byDeadline = (a: AppNotification, b: AppNotification) => {
  const due = (n: AppNotification) =>
    n.input?.deadline ? Date.parse(n.input.deadline) : Number.POSITIVE_INFINITY;
  return due(a) - due(b) || Date.parse(a.createdAt) - Date.parse(b.createdAt);
};

function Heading({ children }: { children: string }) {
  return (
    <h3 className="text-label-sm text-on-surface-variant px-4 pt-3 pb-1 font-semibold tracking-wide uppercase">
      {children}
    </h3>
  );
}

/** A question, with its answer controls when it is a plain approval or choice. */
function ActionItem({ item }: { item: AppNotification }) {
  const input = item.input;
  const inline =
    item.state === 'ACTIVE' &&
    input !== null &&
    (input.kind === 'APPROVAL' || input.kind === 'CHOICE');
  return (
    <li className="border-border flex flex-col gap-1.5 border-b px-4 py-3 last:border-b-0">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-label-sm rounded-md border px-1.5">
          {input
            ? `${input.risk.toLowerCase()} risk`
            : SEVERITY_LABEL[item.severity]}
        </span>
        <span className="text-body-sm text-on-surface-variant">
          {[item.workflowId, item.nodeId].filter(Boolean).join(' · ')}
        </span>
      </div>
      <NotificationLink
        item={item}
        className="text-body-md hover:text-primary font-medium"
      >
        {plain(item.title)}
      </NotificationLink>
      {item.state === 'RESOLVED' ? (
        <p role="status" className="text-body-sm text-on-surface-variant">
          {item.resolution ? plain(item.resolution) : 'Done'}
        </p>
      ) : inline && input ? (
        <InputAnswerForm request={input} />
      ) : (
        <Link
          to={`/inbox?item=${item.id}`}
          className="text-body-sm text-primary"
        >
          Answer…
        </Link>
      )}
    </li>
  );
}

/** What the bell opens: the questions that wait, the newest alerts, mark all read, the inbox. */
export function InboxPopover() {
  const { items, unread, markRead } = useNotifications();
  // a question answered by someone else while this is open stays, saying who answered
  const [shown, setShown] = useState<ReadonlySet<string>>(new Set());
  const active = items
    .filter((i) => i.kind === 'ACTION' && i.state === 'ACTIVE')
    .map((i) => i.id);
  if (active.some((id) => !shown.has(id))) {
    // adjusting state while rendering is how React derives it from props: no extra effect pass
    setShown(new Set([...shown, ...active]));
  }
  const actions = items
    .filter(
      (i) => i.kind === 'ACTION' && (i.state === 'ACTIVE' || shown.has(i.id))
    )
    .sort(byDeadline);
  const recent = items.filter((i) => i.kind === 'INFO').slice(0, MAX_RECENT);
  return (
    <div className="flex max-h-[min(36rem,80vh)] flex-col">
      <div className="border-border flex items-center justify-between border-b px-4 py-2">
        <h2 className="text-body-md font-semibold">
          Notifications
          {unread.length > 0 && (
            <span className="text-label-sm text-on-surface-variant ml-2 font-normal">
              {unread.length} unread
            </span>
          )}
        </h2>
        <div className="flex gap-1.5">
          <IconButton
            icon="done_all"
            label="Mark all read"
            disabled={unread.length === 0}
            onClick={() => markRead()}
          />
          <Tip label="Open inbox">
            <Link
              to="/inbox"
              aria-label="Open inbox"
              className={iconButton('neutral')}
            >
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                open_in_new
              </span>
            </Link>
          </Tip>
        </div>
      </div>
      <div className="min-h-0 overflow-y-auto">
        <Heading>{`Needs action · ${actions.length}`}</Heading>
        {actions.length === 0 ? (
          <p className="text-body-sm text-on-surface-variant px-4 pb-3">
            Nothing waits for you.
          </p>
        ) : (
          <ul>
            {actions.slice(0, MAX_ACTIONS).map((item) => (
              <ActionItem key={item.id} item={item} />
            ))}
          </ul>
        )}
        <Heading>Recent</Heading>
        {recent.length === 0 ? (
          <p className="text-body-sm text-on-surface-variant px-4 pb-3">
            No alerts.
          </p>
        ) : (
          <ul className="pb-2">
            {recent.map((item) => (
              <li key={item.id}>
                <NotificationLink
                  item={item}
                  className="hover:bg-muted flex items-start gap-2.5 px-4 py-2"
                >
                  <span
                    aria-hidden="true"
                    className={`mt-1.5 size-2 flex-none rounded-full ${SEVERITY_DOT[item.severity]}`}
                  />
                  <span className="text-body-sm min-w-0 flex-1">
                    <span className={item.read ? '' : 'font-medium'}>
                      {plain(item.title)}
                    </span>
                    <span className="text-on-surface-variant block truncate">
                      {[item.workflowId, item.nodeId]
                        .filter(Boolean)
                        .join(' · ') || SEVERITY_LABEL[item.severity]}
                      {' · '}
                      {formatRelative(item.createdAt)}
                    </span>
                  </span>
                  {!item.read && (
                    <span
                      role="img"
                      aria-label="unread"
                      className="bg-primary mt-2 size-1.5 flex-none rounded-full"
                    />
                  )}
                </NotificationLink>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
