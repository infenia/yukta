// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { InlineError } from '@/components/common/InlineError';
import { StateMessage } from '@/components/common/StateMessage';
import { Button } from '@/components/ui/button';
import { useMe } from '@/lib/identity';
import { cn } from '@/lib/utils';
import {
  HISTORY_STATES,
  listInputs,
  OPEN_STATES,
  type InputRequest,
} from '../api/inputs';
import { controlErrorMessage } from '../hooks/useExecutionControl';
import {
  outcome,
  responderLabel,
  type AskingNode,
  type OutcomeTone,
} from '../lib/inputNodes';
import {
  formatDateTime,
  formatDuration,
  formatRelative,
} from '../lib/timeFormat';
import { InputRow } from './InputRow';

type Tab = 'open' | 'history';

const TONES: Record<OutcomeTone, string> = {
  success: 'text-success bg-success/10',
  destructive: 'text-destructive bg-destructive/10',
  muted: 'text-muted-foreground bg-muted',
};

/** What a request that ended shows in the answer slot. */
function answerText(request: InputRequest): string {
  if (request.state === 'EXPIRED') return 'No answer in time';
  if (request.state === 'CANCELLED') return 'Cancelled before an answer';
  const { answer } = request;
  if (
    answer?.text &&
    request.kind !== 'APPROVAL' &&
    request.kind !== 'CHOICE'
  ) {
    return `“${answer.text}”`;
  }
  return answer?.decision ?? '';
}

function HistoryItem({ request }: { request: InputRequest }) {
  const me = useMe();
  const { answer } = request;
  const result = outcome(request);
  const when = answer?.answeredAt ?? request.updatedAt;
  return (
    <li className="hover:bg-muted/40 flex items-start gap-3 px-3 py-2.5">
      <span
        data-testid="input-state"
        data-status={request.state}
        title={result.label}
        aria-label={result.label}
        className={cn(
          'mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full',
          TONES[result.tone]
        )}
      >
        <span aria-hidden className="material-symbols-outlined">
          {result.icon}
        </span>
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <span className="text-body-md text-on-surface line-clamp-2">
          {request.prompt}
        </span>
        <span
          className={cn(
            'text-body-md w-fit max-w-full truncate rounded-lg px-2.5 py-0.5 font-medium',
            TONES[result.tone]
          )}
        >
          {request.state === 'ANSWERED' && result.label !== 'Answered'
            ? result.label
            : answerText(request)}
        </span>
        <span className="text-label-sm text-on-surface-variant">
          <span className="font-mono">{request.nodeId}</span>
          {answer && <> · {responderLabel(answer, me)}</>}
          {answer && <> · took {formatDuration(request.createdAt, when)}</>}
        </span>
      </div>
      <time
        dateTime={when}
        title={formatDateTime(when)}
        className="text-label-sm text-on-surface-variant shrink-0"
      >
        {formatRelative(when)}
      </time>
    </li>
  );
}

function HistoryList({
  items,
  currentExecutionId,
}: {
  items: InputRequest[];
  currentExecutionId: string | null;
}) {
  const groups = new Map<string, InputRequest[]>();
  for (const request of items) {
    groups.set(request.executionId, [
      ...(groups.get(request.executionId) ?? []),
      request,
    ]);
  }
  const grouped = groups.size > 1;
  return (
    <>
      {[...groups].map(([executionId, requests]) => (
        <li key={executionId} className="list-none">
          {grouped && (
            <p className="text-overline text-on-surface-variant pt-2">
              {executionId === currentExecutionId ? 'This run' : 'Run'} ·{' '}
              {executionId.slice(0, 8)} ·{' '}
              {formatDateTime(requests[0].createdAt)}
            </p>
          )}
          <ul className="divide-border border-border bg-card shadow-card mt-1 divide-y overflow-hidden rounded-xl border">
            {requests.map((request) => (
              <HistoryItem key={request.id} request={request} />
            ))}
          </ul>
        </li>
      ))}
    </>
  );
}

/** Where a run asks, before it has: what will show here, and which nodes will ask. */
function EmptyWaiting({
  askingNodes,
  hasRun,
  live,
  historyCount,
  onHistory,
  onNode,
}: {
  askingNodes: AskingNode[];
  hasRun: boolean;
  live: boolean;
  historyCount: number;
  onHistory: () => void;
  onNode?: (nodeId: string) => void;
}) {
  const caughtUp = hasRun && (!live || historyCount > 0);
  const [icon, title] = !hasRun
    ? ['help', 'This workflow asks for input']
    : caughtUp
      ? ['task_alt', 'All caught up']
      : ['hourglass_empty', 'Nothing to answer yet'];
  return (
    <div className="flex flex-col items-center gap-2">
      <StateMessage compact icon={icon} title={title} className="w-full">
        {hasRun
          ? 'No input requests are waiting.'
          : 'When it runs, its questions show up here and you answer them without leaving the page.'}
      </StateMessage>
      {hasRun && !caughtUp && askingNodes.length > 0 && (
        <p className="text-body-sm text-on-surface-variant">
          The run is going. A question appears here as soon as one of these
          nodes asks.
        </p>
      )}
      {askingNodes.length > 0 && (
        <ul className="flex flex-wrap justify-center gap-2">
          {askingNodes.map((node) => (
            <li key={node.nodeId}>
              {onNode ? (
                <button
                  type="button"
                  title={`Open ${node.nodeId}`}
                  onClick={() => onNode(node.nodeId)}
                  className="border-border bg-card text-label-sm hover:bg-muted rounded-full border px-2.5 py-1"
                >
                  {node.nodeId} · {node.label}
                </button>
              ) : (
                <span className="border-border bg-card text-label-sm rounded-full border px-2.5 py-1">
                  {node.nodeId} · {node.label}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {caughtUp && historyCount > 0 && (
        <Button size="sm" variant="ghost" onClick={onHistory}>
          See history ({historyCount})
        </Button>
      )}
    </div>
  );
}

/** Open and answered input requests of a workflow; the dock tab and its panel share the cache. */
export function useInputQueries(
  sessionId: string,
  workflowId: string,
  live: boolean
) {
  const query = (name: 'open' | 'history') => ({
    queryKey: ['inputs', sessionId, workflowId, name],
    queryFn: () =>
      listInputs(sessionId, {
        workflowId,
        state: name === 'open' ? OPEN_STATES : HISTORY_STATES,
      }),
    // only a live run can start to ask, so only then poll
    refetchInterval: live ? (5000 as const) : (false as const),
  });
  return { open: useQuery(query('open')), history: useQuery(query('history')) };
}

function Count({ n, hot }: { n: number; hot: boolean }) {
  return (
    <span
      className={cn(
        'text-label-sm rounded-full px-1.5 tabular-nums',
        hot ? 'bg-warning text-on-primary' : 'bg-muted text-on-surface-variant'
      )}
    >
      {n}
    </span>
  );
}

/** The input requests of a workflow: those that wait for an answer, and the history. */
export function InputsPanel({
  sessionId,
  workflowId,
  live = true,
  askingNodes = [],
  hasRun = true,
  currentExecutionId = null,
  onNode,
}: {
  sessionId: string;
  workflowId: string;
  live?: boolean;
  /** The nodes of the workflow that can ask; listed while nothing is waiting. */
  askingNodes?: AskingNode[];
  /** False before any run exists. */
  hasRun?: boolean;
  /** The run on screen, to mark its group in the history. */
  currentExecutionId?: string | null;
  /** Opens a node on the canvas; makes the node names clickable. */
  onNode?: (nodeId: string) => void;
}) {
  const [tab, setTab] = useState<Tab>('open');
  const [newArrived, setNewArrived] = useState(false);
  const queries = useInputQueries(sessionId, workflowId, live);
  const waiting = queries.open.data?.length ?? 0;
  const done = queries.history.data?.length ?? 0;
  // a request that arrives while the history is open is announced, not forced on the reader
  const [seen, setSeen] = useState(waiting);
  if (waiting !== seen) {
    setSeen(waiting);
    if (waiting > seen && tab === 'history') setNewArrived(true);
  }
  const inputs = tab === 'open' ? queries.open : queries.history;
  const items = inputs.data ?? [];
  const show = (next: Tab) => {
    setTab(next);
    setNewArrived(false);
  };

  return (
    <section
      aria-label="Inputs"
      className="@container flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-4 py-3"
    >
      <p role="status" className="sr-only">
        {waiting > 0
          ? `${waiting} input request${waiting === 1 ? '' : 's'} waiting`
          : ''}
      </p>
      <div className="flex items-center gap-3">
        <div
          role="tablist"
          className="bg-muted flex gap-0.5 rounded-full p-0.5"
        >
          {(
            [
              ['open', 'Waiting', waiting],
              ['history', 'History', done],
            ] as const
          ).map(([name, label, count]) => (
            <button
              key={name}
              type="button"
              role="tab"
              aria-selected={tab === name}
              onClick={() => show(name)}
              className={cn(
                'text-label-md transition-standard flex h-7 items-center gap-1.5 rounded-full px-3',
                tab === name
                  ? 'bg-card text-on-surface shadow-card'
                  : 'text-on-surface-variant'
              )}
            >
              {label}
              <Count n={count} hot={name === 'open' && count > 0} />
            </button>
          ))}
        </div>
        {newArrived && (
          <button
            type="button"
            onClick={() => show('open')}
            className="text-label-md text-attention flex items-center gap-1.5"
          >
            <span
              aria-hidden
              className="live-dot bg-attention size-[7px] rounded-full"
            />
            New request waiting
          </button>
        )}
      </div>
      <div className="flex flex-col gap-2">
        <InlineError
          message={inputs.error ? controlErrorMessage(inputs.error) : null}
        />
        {inputs.isSuccess && items.length === 0 && tab === 'open' && (
          <EmptyWaiting
            askingNodes={askingNodes}
            hasRun={hasRun}
            live={live}
            historyCount={done}
            onHistory={() => show('history')}
            onNode={onNode}
          />
        )}
        {inputs.isSuccess && items.length === 0 && tab === 'history' && (
          <StateMessage compact icon="history" title="No history yet">
            Answered, expired and cancelled requests are listed here.
          </StateMessage>
        )}
        {tab === 'open' ? (
          <ul className="flex flex-col gap-3">
            {items.map((request) => (
              <InputRow key={request.id} request={request} onNode={onNode} />
            ))}
          </ul>
        ) : (
          <ul>
            <HistoryList
              items={items}
              currentExecutionId={currentExecutionId}
            />
          </ul>
        )}
      </div>
    </section>
  );
}
