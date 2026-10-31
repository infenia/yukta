// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { StatusPill } from '@/features/workflow/components/StatusPill';
import {
  formatClockTime,
  formatDuration,
} from '@/features/workflow/lib/timeFormat';
import { searchExecutions } from '@/features/runs/api/searchExecutions';
import { parseFilters } from '@/features/runs/lib/filters';

const RECENT = 5;

/** The session's newest runs; the full list is the Runs page, filtered to this session. */
export function RecentRuns({ sessionId }: { sessionId: string }) {
  const { data: runs = [] } = useQuery({
    queryKey: ['runs', 'recent', sessionId],
    queryFn: () =>
      searchExecutions(
        parseFilters(new URLSearchParams({ session: sessionId })),
        RECENT
      ),
  });
  const session = encodeURIComponent(sessionId);

  return (
    <section className="flex flex-col gap-3 px-4 pb-8 sm:px-6">
      <div className="flex items-center gap-3">
        <h2 className="text-headline-md text-on-surface">Recent runs</h2>
        <Link
          to={`/runs?session=${session}`}
          className="text-label-md text-primary ml-auto"
        >
          All runs of {sessionId}
        </Link>
      </div>
      {runs.length === 0 ? (
        <p className="text-body-sm text-muted-foreground">
          No runs yet. Start one from a workflow, the CLI or an agent over MCP.
        </p>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="px-4">Status</TableHead>
                <TableHead className="px-4">Run</TableHead>
                <TableHead className="px-4">Workflow</TableHead>
                <TableHead className="px-4">Started</TableHead>
                <TableHead className="px-4">Duration</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {runs.map((run) => (
                <TableRow key={run.executionId}>
                  <TableCell className="px-4">
                    <StatusPill status={run.status} />
                  </TableCell>
                  <TableCell className="px-4">
                    <Link
                      to={`/sessions/${session}/workflow/${encodeURIComponent(run.workflowId)}?exec=${encodeURIComponent(run.executionId)}`}
                      title={run.executionId}
                      className="text-code-sm text-primary"
                    >
                      {run.executionId.slice(0, 8)}
                    </Link>
                  </TableCell>
                  <TableCell className="text-code-sm px-4">
                    {run.workflowId}
                  </TableCell>
                  <TableCell className="text-code-sm px-4 tabular-nums">
                    {formatClockTime(run.startTime)}
                  </TableCell>
                  <TableCell className="text-code-sm px-4 tabular-nums">
                    {formatDuration(run.startTime, run.endTime)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
    </section>
  );
}
