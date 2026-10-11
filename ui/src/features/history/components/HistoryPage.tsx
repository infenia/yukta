// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { api, fetchApi } from '@/lib/apiClient';
import { PageHeader } from '@/components/layout/PageHeader';
import { StateMessage } from '@/components/common/StateMessage';
import { useSessionSummaries } from '@/features/sessions/hooks/useSessionSummaries';
import { StatusChip } from '@/features/workflow/components/StatusChip';
import { formatClockTime } from '@/features/workflow/lib/timeFormat';
import type { ExecutionSummary } from '@/features/workflow/types/workflow';

/** Runs shown per session; the endpoint is cursor-paged but recent runs are what matters here. */
const HISTORY_LIMIT = 50;

export function getSessionHistory(
  sessionId: string
): Promise<ExecutionSummary[]> {
  return fetchApi<ExecutionSummary[]>(
    `${api`/api/workflow/${sessionId}/history`}?limit=${HISTORY_LIMIT}`
  );
}

/** Recent runs of one session, newest first, linking to each run's workflow page. */
export function HistoryPage() {
  const sessions = useSessionSummaries();
  const [chosen, setChosen] = useState('');
  const sessionId = chosen || sessions.data?.[0]?.sessionId || '';
  const history = useQuery({
    queryKey: ['sessions', sessionId, 'history'],
    queryFn: () => getSessionHistory(sessionId),
    enabled: sessionId !== '',
  });

  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        title="History"
        description="Recent runs of a session, newest first."
        actions={
          <select
            aria-label="Session"
            value={sessionId}
            onChange={(event) => setChosen(event.target.value)}
            className="border-outline-variant bg-surface text-on-surface h-8 border px-2 text-sm"
          >
            {sessions.data?.map((session) => (
              <option key={session.sessionId} value={session.sessionId}>
                {session.name || session.sessionId}
              </option>
            ))}
          </select>
        }
      />

      {sessions.error || history.error ? (
        <StateMessage
          tone="error"
          title="Failed to load history"
          onRetry={() => void history.refetch()}
        />
      ) : history.isPending && sessionId ? (
        <p className="text-body-md text-on-surface-variant">Loading…</p>
      ) : !history.data?.length ? (
        <StateMessage title="No runs yet.">
          Run a workflow and it will show up here.
        </StateMessage>
      ) : (
        <table className="border-outline-variant w-full border text-left text-sm">
          <thead className="border-outline-variant text-on-surface-variant bg-surface-container-high text-label-sm border-b tracking-wider uppercase">
            <tr>
              <th className="px-4 py-2">Execution</th>
              <th className="px-4">Workflow</th>
              <th className="px-4">Status</th>
              <th className="px-4">Started</th>
              <th className="px-4">Ended</th>
            </tr>
          </thead>
          <tbody>
            {history.data.map((run, index) => (
              <tr
                key={run.executionId}
                className={`transition-standard hover:bg-surface-container-high ${index % 2 ? 'bg-surface-container-low' : ''}`}
              >
                <td className="text-code-sm px-4 py-3">
                  <Link
                    className="text-primary"
                    to={`/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(run.workflowId)}?exec=${encodeURIComponent(run.executionId)}`}
                  >
                    {run.executionId}
                  </Link>
                </td>
                <td className="px-4">{run.workflowId}</td>
                <td className="px-4">
                  <StatusChip status={run.status} />
                </td>
                <td className="text-code-sm px-4">
                  {formatClockTime(run.startTime)}
                </td>
                <td className="text-code-sm px-4">
                  {formatClockTime(run.endTime)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
