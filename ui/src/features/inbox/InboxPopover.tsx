// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link } from 'react-router';
import { Button } from '@/components/ui/button';
import { InputAnswerForm } from '@/features/workflow/components/InputAnswerForm';
import type { AppNotification } from './api';
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
      <p className="text-body-md font-medium">{plain(item.title)}</p>
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
    <div className="flex flex-col">
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
      <div className="flex items-center justify-between">
        <Heading>Recent</Heading>
        <Button
          variant="link"
          size="sm"
          disabled={unread.length === 0}
          onClick={() => markRead()}
        >
          Mark all read
        </Button>
      </div>
      {recent.length === 0 ? (
        <p className="text-body-sm text-on-surface-variant px-4 pb-3">
          No alerts.
        </p>
      ) : (
        <ul className="pb-2">
          {recent.map((item) => (
            <li key={item.id} className="flex items-start gap-2.5 px-4 py-1.5">
              <span
                aria-hidden="true"
                className={`mt-1.5 size-2 flex-none rounded-full ${SEVERITY_DOT[item.severity]}`}
              />
              <span className="text-body-sm">
                <span className={item.read ? '' : 'font-medium'}>
                  {plain(item.title)}
                </span>
                <span className="text-on-surface-variant block">
                  {SEVERITY_LABEL[item.severity]}
                  {item.read ? '' : ' · unread'}
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
      <Link
        to="/inbox"
        className="border-border text-body-sm text-primary border-t px-4 py-3 text-center font-medium"
      >
        Open inbox
      </Link>
    </div>
  );
}
