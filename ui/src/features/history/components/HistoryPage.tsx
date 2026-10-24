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
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';

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
            className="border-outline-variant bg-surface text-on-surface h-8 rounded-lg border px-2 text-sm"
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
          onRetry={() =>
            void (sessions.error ? sessions.refetch() : history.refetch())
          }
        />
      ) : history.isPending && sessionId ? (
        <StateMessage tone="loading" title="Loading…" />
      ) : !history.data?.length ? (
        <StateMessage title="No runs yet.">
          Run a workflow and it will show up here.
        </StateMessage>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="px-4">Execution</TableHead>
                <TableHead className="px-4">Workflow</TableHead>
                <TableHead className="px-4">Status</TableHead>
                <TableHead className="px-4">Started</TableHead>
                <TableHead className="px-4">Ended</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {history.data.map((run) => (
                <TableRow key={run.executionId}>
                  <TableCell className="text-code-sm px-4 py-3">
                    <Link
                      className="text-primary"
                      to={`/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(run.workflowId)}?exec=${encodeURIComponent(run.executionId)}`}
                    >
                      {run.executionId}
                    </Link>
                  </TableCell>
                  <TableCell className="px-4">{run.workflowId}</TableCell>
                  <TableCell className="px-4">
                    <StatusChip status={run.status} />
                  </TableCell>
                  <TableCell className="text-code-sm px-4">
                    {formatClockTime(run.startTime)}
                  </TableCell>
                  <TableCell className="text-code-sm px-4">
                    {formatClockTime(run.endTime)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
    </div>
  );
}
