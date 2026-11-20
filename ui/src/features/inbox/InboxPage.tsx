// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { PageHeader } from '@/components/layout/PageHeader';
import { StateMessage } from '@/components/common/StateMessage';
import { IconButton } from '@/components/common/IconButton';
import { Button } from '@/components/ui/button';
import { iconButton } from '@/components/ui/iconTone';
import { Tip } from '@/components/ui/tooltip';
import { NativeSelect } from '@/components/ui/native-select';
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTitle,
} from '@/components/ui/sheet';
import { PreferencesSheet } from './PreferencesSheet';
import { Tabs } from '@/components/ui/tabs';
import { useNow } from '@/features/control/dashboard/useNow';
import { InputAnswerForm } from '@/features/workflow/components/InputAnswerForm';
import {
  ApprovalChain,
  remaining,
} from '@/features/workflow/components/InputRow';
import type { AppNotification, NotificationSeverity } from './api';
import { formatRelative } from '@/features/workflow/lib/timeFormat';
import { api } from '@/lib/apiClient';
import { NotificationLink } from './NotificationLink';
import { useNotifications } from './NotificationsContext';
import { workflowHref } from './notificationLink';
import { plain, SEVERITY_DOT, SEVERITY_LABEL } from './severity';

type Tab = 'needs-action' | 'all' | 'resolved';
const RANK: Record<NotificationSeverity, number> = {
  INFO: 0,
  WARN: 1,
  ERROR: 2,
};

/** A question the sheet answers: text and structured answers need room; buttons do not. */
const needsSheet = (n: AppNotification) =>
  n.state === 'ACTIVE' &&
  n.input !== null &&
  (n.input.kind === 'TEXT' || n.input.kind === 'STRUCTURED');

const inTab = (n: AppNotification, tab: Tab) =>
  tab === 'resolved'
    ? n.state === 'RESOLVED'
    : tab === 'needs-action'
      ? n.kind === 'ACTION' && n.state === 'ACTIVE'
      : true;

const CHIP =
  'text-label-sm text-on-surface-variant hover:text-primary hover:bg-muted rounded-full border px-2 py-0.5';

/** Session, workflow and node of an item, each a link to its page. */
function Where({ item }: { item: AppNotification }) {
  const { sessionId, workflowId, nodeId } = item;
  const workflow = workflowHref({ ...item, executionId: null, nodeId: null });
  const node = nodeId ? workflowHref(item) : null;
  if (!sessionId && !workflowId && !nodeId) return null;
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {sessionId && (
        <Link to={api`/sessions/${sessionId}`} className={CHIP}>
          {sessionId}
        </Link>
      )}
      {workflowId &&
        (workflow ? (
          <Link to={workflow} className={CHIP}>
            {workflowId}
          </Link>
        ) : (
          <span className={CHIP}>{workflowId}</span>
        ))}
      {nodeId &&
        (node ? (
          <Link to={node} className={CHIP}>
            {nodeId}
          </Link>
        ) : (
          <span className={CHIP}>{nodeId}</span>
        ))}
    </div>
  );
}

function Row({
  item,
  selected,
  onAnswer,
}: {
  item: AppNotification;
  selected: boolean;
  onAnswer: () => void;
}) {
  const { markRead, dismiss } = useNotifications();
  const now = useNow();
  const ref = useRef<HTMLLIElement>(null);
  useEffect(() => {
    if (selected) ref.current?.scrollIntoView?.({ block: 'center' });
  }, [selected]);
  const input = item.input;
  const live = item.state === 'ACTIVE';
  return (
    <li
      ref={ref}
      id={`item-${item.id}`}
      aria-current={selected ? 'true' : undefined}
      className={`flex flex-col gap-2 p-4 ${selected ? 'bg-primary/5 ring-primary ring-2 ring-inset' : ''}`}
    >
      <div className="flex items-start gap-3">
        <span
          aria-hidden="true"
          className={`mt-2 size-2 flex-none rounded-full ${SEVERITY_DOT[item.severity]}`}
        />
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <div className="flex flex-wrap items-center gap-2">
            <NotificationLink
              item={item}
              className={`text-body-md hover:text-primary ${item.read ? '' : 'font-semibold'}`}
            >
              {plain(item.title)}
            </NotificationLink>
            {!item.read && (
              <span
                className="bg-primary size-1.5 rounded-full"
                role="img"
                aria-label="New"
              />
            )}
          </div>
          <div className="text-label-sm text-on-surface-variant flex flex-wrap items-center gap-x-2 gap-y-1">
            <span>{SEVERITY_LABEL[item.severity]}</span>
            {input && <span>{input.risk.toLowerCase()} risk</span>}
            {input?.deadline && live && (
              <span>
                tier {input.tier} · {remaining(input.deadline, now)}
              </span>
            )}
            <span>{formatRelative(item.createdAt, now)}</span>
          </div>
          <Where item={item} />
          {item.body && (
            <p className="text-body-sm text-on-surface-variant whitespace-pre-wrap">
              {plain(item.body)}
            </p>
          )}
          {input && <ApprovalChain request={input} you />}
          {!live && (
            <p role="status" className="text-body-sm text-on-surface-variant">
              {item.resolution ? plain(item.resolution) : 'Done'}
            </p>
          )}
          {live && input && !needsSheet(item) && (
            <InputAnswerForm request={input} />
          )}
        </div>
        <div className="flex flex-none gap-1.5">
          {live && needsSheet(item) && (
            <IconButton
              icon="reply"
              label="Answer…"
              tone="primary"
              onClick={onAnswer}
            />
          )}
          <Tip label="Open">
            <NotificationLink
              item={item}
              aria-label="Open"
              className={iconButton('neutral')}
            >
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                open_in_new
              </span>
            </NotificationLink>
          </Tip>
          {!item.read && (
            <IconButton
              icon="mark_email_read"
              label="Mark read"
              onClick={() => markRead([item.id])}
            />
          )}
          {!(live && item.kind === 'ACTION') && (
            <IconButton
              icon="close"
              label="Dismiss"
              onClick={() => dismiss(item.id)}
            />
          )}
        </div>
      </div>
    </li>
  );
}

/** The question in a sheet, for answers that need a text area. */
function AnswerSheet({
  item,
  onClose,
}: {
  item: AppNotification | undefined;
  onClose: () => void;
}) {
  const input = item?.input ?? null;
  return (
    <Sheet
      open={item !== undefined && input !== null}
      onOpenChange={(o) => !o && onClose()}
    >
      <SheetContent>
        {item && input && (
          <>
            <SheetTitle>{plain(item.title)}</SheetTitle>
            <p className="text-body-md whitespace-pre-wrap">
              {plain(input.prompt)}
            </p>
            <ApprovalChain request={input} you />
            <InputAnswerForm request={input} onAnswered={onClose} />
            <SheetClose render={<Button variant="ghost" />}>Close</SheetClose>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}

/**
 * Everything that needs my action, everything I was told and what ended, with filters. `?item=<id>`
 * is where every desktop notification and CLI link goes: it marks that row and, for a question that
 * needs a text answer, opens the sheet.
 */
export function InboxPage() {
  const { items, loaded, connected, unread, markRead } = useNotifications();
  const [params, setParams] = useSearchParams();
  const [tab, setTab] = useState<Tab>('needs-action');
  const [severity, setSeverity] = useState('');
  const [session, setSession] = useState('');
  const [category, setCategory] = useState('');
  const wanted = params.get('item');
  const target = items.find((i) => i.id === wanted);
  const [sheetFor, setSheetFor] = useState<string | null>(null);

  // a deep link shows the tab its item belongs to; the filters never hide it
  const [followed, setFollowed] = useState<string | null>(null);
  if (target && followed !== target.id) {
    setFollowed(target.id);
    setTab(
      target.state === 'RESOLVED'
        ? 'resolved'
        : target.kind === 'ACTION'
          ? 'needs-action'
          : 'all'
    );
    setSeverity('');
    setSession('');
    setCategory('');
    if (needsSheet(target)) setSheetFor(target.id);
  }

  const sessions = useMemo(
    () =>
      [
        ...new Set(
          items.map((i) => i.sessionId).filter((s): s is string => !!s)
        ),
      ].sort(),
    [items]
  );
  const categories = useMemo(
    () => [...new Set(items.map((i) => i.category))].sort(),
    [items]
  );
  const shown = items
    .filter((i) => inTab(i, tab))
    .filter(
      (i) =>
        i.id === wanted ||
        !severity ||
        RANK[i.severity] >= RANK[severity as NotificationSeverity]
    )
    .filter((i) => i.id === wanted || !session || i.sessionId === session)
    .filter((i) => i.id === wanted || !category || i.category === category);
  const counts = {
    'needs-action': items.filter((i) => inTab(i, 'needs-action')).length,
    all: items.length,
    resolved: items.filter((i) => inTab(i, 'resolved')).length,
  };
  const close = () => {
    setSheetFor(null);
    const next = new URLSearchParams(params);
    next.delete('item');
    setParams(next, { replace: true });
  };

  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        backdrop
        title="Inbox"
        description="Questions that wait for you, what you were told and what has ended."
        actions={
          <div className="flex gap-2">
            <PreferencesSheet sessions={sessions} />
            <IconButton
              icon="done_all"
              label="Mark all read"
              disabled={unread.length === 0}
              onClick={() => markRead()}
            />
          </div>
        }
      />
      {!connected && (
        <p role="status" className="bg-muted text-body-sm rounded-lg px-3 py-2">
          Live updates are paused: the list is refreshed every 30 s.
        </p>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Tabs
          ariaLabel="Inbox"
          idPrefix="inbox"
          value={tab}
          onChange={setTab}
          items={[
            {
              id: 'needs-action',
              label: 'Needs action',
              count: counts['needs-action'],
            },
            { id: 'all', label: 'All', count: counts.all },
            { id: 'resolved', label: 'Resolved', count: counts.resolved },
          ]}
        />
        <div className="flex flex-wrap gap-2">
          <NativeSelect
            aria-label="Severity"
            value={severity}
            onChange={(e) => setSeverity(e.target.value)}
          >
            <option value="">Any severity</option>
            <option value="WARN">Warning or above</option>
            <option value="ERROR">Error</option>
          </NativeSelect>
          <NativeSelect
            aria-label="Session"
            value={session}
            onChange={(e) => setSession(e.target.value)}
          >
            <option value="">All sessions</option>
            {sessions.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </NativeSelect>
          <NativeSelect
            aria-label="Category"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
          >
            <option value="">Any category</option>
            {categories.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </NativeSelect>
        </div>
      </div>
      <div
        id="inbox-tabpanel"
        role="tabpanel"
        aria-labelledby={`inbox-tab-${tab}`}
      >
        {wanted && loaded && !target && (
          <p
            role="alert"
            className="border-border text-body-md mb-3 rounded-xl border p-3"
          >
            This item is not available to you. It may have been removed, or it
            was meant for someone else.
          </p>
        )}
        {!loaded ? (
          <StateMessage tone="loading" title="Loading…" />
        ) : shown.length === 0 ? (
          <StateMessage
            title={
              tab === 'needs-action'
                ? "You're all caught up"
                : tab === 'resolved'
                  ? 'Nothing has ended yet'
                  : 'Nothing here'
            }
          >
            Questions from your workflows and alerts show up here.
          </StateMessage>
        ) : (
          <ul className="divide-border border-border bg-card shadow-card divide-y overflow-hidden rounded-xl border">
            {shown.map((item) => (
              <Row
                key={item.id}
                item={item}
                selected={item.id === wanted}
                onAnswer={() => setSheetFor(item.id)}
              />
            ))}
          </ul>
        )}
      </div>
      <AnswerSheet
        item={items.find((i) => i.id === sheetFor)}
        onClose={close}
      />
    </div>
  );
}
