// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { InlineError } from '@/components/common/InlineError';
import {
  HISTORY_STATES,
  listInputs,
  OPEN_STATES,
  type InputRequest,
} from '../api/inputs';
import { controlErrorMessage } from '../hooks/useExecutionControl';
import { InputRow } from './InputRow';
import { StatusPill } from './StatusPill';

const TABS = ['open', 'history'] as const;

function HistoryRow({ request }: { request: InputRequest }) {
  const { answer } = request;
  return (
    <li className="border-border flex flex-wrap items-center gap-2 border-b py-2 last:border-b-0">
      <StatusPill status={request.state} testId="input-state" />
      <span className="text-label-sm font-mono">{request.nodeId}</span>
      <span className="text-body-sm text-on-surface min-w-0 flex-1 truncate">
        {request.prompt}
      </span>
      {answer && (
        <span className="text-label-sm text-on-surface-variant">
          “{answer.decision}” by {answer.responderId}
        </span>
      )}
    </li>
  );
}

/** The input requests of a workflow: those that wait for an answer, and the history. */
export function InputsPanel({
  sessionId,
  workflowId,
  live = true,
}: {
  sessionId: string;
  workflowId: string;
  /** The run on screen is going: only then can a step start to ask, so only then poll. */
  live?: boolean;
}) {
  const [tab, setTab] = useState<(typeof TABS)[number]>('open');
  const query = (name: (typeof TABS)[number]) => ({
    queryKey: ['inputs', sessionId, workflowId, name],
    queryFn: () =>
      listInputs(sessionId, {
        workflowId,
        state: name === 'open' ? OPEN_STATES : HISTORY_STATES,
      }),
    refetchInterval: live ? (5000 as const) : (false as const),
  });
  const openInputs = useQuery(query('open'));
  const history = useQuery(query('history'));
  const inputs = tab === 'open' ? openInputs : history;
  const items = inputs.data ?? [];
  // Nothing has been asked in this workflow: show no panel, so a run without inputs keeps its
  // screen to itself. Once it has shown a request it stays, to say when they are all answered.
  const [seen, setSeen] = useState(false);
  const any = (openInputs.data?.length ?? 0) + (history.data?.length ?? 0) > 0;
  if (any && !seen) setSeen(true);
  if (!any && !seen && !openInputs.isError && !history.isError) return null;

  return (
    <section
      aria-label="Inputs"
      className="border-outline-variant bg-surface-container-low flex flex-col gap-2 border-b px-4 py-3 sm:px-6"
    >
      <div role="tablist" className="flex gap-4">
        {TABS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={tab === name}
            onClick={() => setTab(name)}
            className={`text-label-md h-8 border-b-2 capitalize ${
              tab === name
                ? 'border-primary text-on-surface'
                : 'text-on-surface-variant border-transparent'
            }`}
          >
            {name === 'open' ? 'Inputs' : 'History'}
          </button>
        ))}
      </div>
      <div aria-live="polite" className="flex flex-col gap-2">
        <InlineError
          message={inputs.error ? controlErrorMessage(inputs.error) : null}
        />
        {inputs.isSuccess && items.length === 0 && (
          <p className="text-body-sm text-on-surface-variant">
            {tab === 'open'
              ? 'No input requests are waiting.'
              : 'No answered, expired or cancelled requests yet.'}
          </p>
        )}
        <ul className="flex flex-col gap-2">
          {items.map((request) =>
            tab === 'open' ? (
              <InputRow key={request.id} request={request} />
            ) : (
              <HistoryRow key={request.id} request={request} />
            )
          )}
        </ul>
      </div>
    </section>
  );
}
