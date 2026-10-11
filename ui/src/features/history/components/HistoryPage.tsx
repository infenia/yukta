// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { api, fetchApi } from '@/lib/apiClient';
import { useSessionSummaries } from '@/features/sessions/hooks/useSessionSummaries';
import { StatusChip } from '@/features/workflow/components/StatusChip';
import { formatClockTime } from '@/features/workflow/lib/timeFormat';
import type { ExecutionSummary } from '@/features/workflow/types/workflow';

/** Runs shown per session; the endpoint is cursor-paged but recent runs are what matters here. */
const HISTORY_LIMIT = 50;

export function getSessionHistory(sessionId: string): Promise<ExecutionSummary[]> {
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
    <div className="flex flex-1 flex-col gap-4 p-6">
      <div className="flex items-center gap-3">
        <h1 className="text-2xl font-bold text-on-surface">History</h1>
        <select
          aria-label="Session"
          value={sessionId}
          onChange={(event) => setChosen(event.target.value)}
          className="ml-auto h-8 border border-outline-variant bg-surface px-2 text-sm text-on-surface"
        >
          {sessions.data?.map((session) => (
            <option key={session.sessionId} value={session.sessionId}>
              {session.name || session.sessionId}
            </option>
          ))}
        </select>
      </div>

      {sessions.error || history.error ? (
        <p role="alert" className="text-error">
          Failed to load history
        </p>
      ) : history.isPending && sessionId ? (
        <p className="text-on-surface-variant">Loading…</p>
      ) : !history.data?.length ? (
        <p className="text-on-surface-variant">No runs yet.</p>
      ) : (
        <table className="w-full text-left text-sm">
          <thead className="border-b border-outline-variant text-xs tracking-wider text-on-surface-variant uppercase">
            <tr>
              <th className="py-2">Execution</th>
              <th>Workflow</th>
              <th>Status</th>
              <th>Started</th>
              <th>Ended</th>
            </tr>
          </thead>
          <tbody>
            {history.data.map((run, index) => (
              <tr
                key={run.executionId}
                className={index % 2 ? 'bg-surface-container-low' : undefined}
              >
                <td className="py-2 font-mono text-xs">
                  <Link
                    className="text-primary"
                    to={`/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(run.workflowId)}?exec=${encodeURIComponent(run.executionId)}`}
                  >
                    {run.executionId}
                  </Link>
                </td>
                <td>{run.workflowId}</td>
                <td>
                  <StatusChip status={run.status} />
                </td>
                <td className="font-mono text-xs">{formatClockTime(run.startTime)}</td>
                <td className="font-mono text-xs">{formatClockTime(run.endTime)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
