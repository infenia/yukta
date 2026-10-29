// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { NativeSelect } from '@/components/ui/native-select';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { PageHeader } from '@/components/layout/PageHeader';
import { StateMessage } from '@/components/common/StateMessage';
import { useSessionSummaries } from '@/features/sessions/hooks/useSessionSummaries';
import { useSessionWorkflows } from '@/features/sessions/hooks/useSessionWorkflows';
import { StatusPill } from '@/features/workflow/components/StatusPill';
import { toStatusDisplay } from '@/features/workflow/lib/statusMapper';
import {
  formatClockTime,
  formatDuration,
} from '@/features/workflow/lib/timeFormat';
import { MAX_RUNS, searchExecutions } from '../api/searchExecutions';
import {
  RUN_STATUSES,
  parseFilters,
  toParams,
  type Range,
  type RunFilters,
} from '../lib/filters';

/** Runs fetched first, and added by each "Load more". */
const PAGE = 100;

const RANGE_LABELS: [Range, string][] = [
  ['', 'Any time'],
  ['1h', 'Last hour'],
  ['24h', 'Last 24 hours'],
  ['7d', 'Last 7 days'],
  ['custom', 'Custom…'],
];

/** Runs of every session, newest first, narrowed by filters that live in the URL. */
export function RunsPage() {
  const [params, setParams] = useSearchParams();
  const filters = parseFilters(params);
  const [limit, setLimit] = useState(PAGE);
  const sessions = useSessionSummaries();
  const runs = useQuery({
    queryKey: ['runs', filters, limit],
    queryFn: () => searchExecutions(filters, limit),
    placeholderData: (previous) => previous,
  });
  const workflows = useSessionWorkflows(filters.session);

  const set = (change: Partial<RunFilters>) => {
    setParams(toParams({ ...filters, ...change }), { replace: true });
    setLimit(PAGE);
  };

  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        title="Runs"
        description="Runs of every session, newest first."
      />
      <div className="flex flex-wrap items-center gap-2">
        <NativeSelect
          aria-label="Session"
          value={filters.session}
          onChange={(event) =>
            set({ session: event.target.value, workflow: '' })
          }
          className="w-48"
        >
          <option value="">All sessions</option>
          {sessions.data?.map((session) => (
            <option key={session.sessionId} value={session.sessionId}>
              {session.name || session.sessionId}
            </option>
          ))}
        </NativeSelect>
        <NativeSelect
          aria-label="Workflow"
          value={filters.workflow}
          disabled={!filters.session}
          onChange={(event) => set({ workflow: event.target.value })}
          className="w-48"
        >
          <option value="">All workflows</option>
          {workflows.data?.map((workflow) => (
            <option key={workflow.workflowId}>{workflow.workflowId}</option>
          ))}
        </NativeSelect>
        <NativeSelect
          aria-label="Status"
          value={filters.status}
          onChange={(event) => set({ status: event.target.value })}
          className="w-40"
        >
          <option value="">Any status</option>
          {RUN_STATUSES.map((status) => (
            <option key={status} value={status}>
              {/* FAILURE and ERROR both read "Failed"; the filter still tells them apart */}
              {status === 'ERROR'
                ? 'Failed (error)'
                : toStatusDisplay(status).label}
            </option>
          ))}
        </NativeSelect>
        <Input
          aria-label="Run id contains"
          placeholder="Run id contains…"
          value={filters.text}
          onChange={(event) => set({ text: event.target.value.trim() })}
          className="w-48"
        />
        <NativeSelect
          aria-label="Time range"
          value={filters.range}
          onChange={(event) => set({ range: event.target.value as Range })}
          className="w-44"
        >
          {RANGE_LABELS.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </NativeSelect>
        {filters.range === 'custom' && (
          <>
            <Input
              type="datetime-local"
              aria-label="Since"
              value={filters.since}
              onChange={(event) => set({ since: event.target.value })}
              className="w-52"
            />
            <Input
              type="datetime-local"
              aria-label="Until"
              value={filters.until}
              onChange={(event) => set({ until: event.target.value })}
              className="w-52"
            />
          </>
        )}
      </div>

      {runs.error ? (
        <StateMessage
          tone="error"
          title="Failed to load runs"
          onRetry={() => void runs.refetch()}
        />
      ) : runs.isPending ? (
        <StateMessage tone="loading" title="Loading…" />
      ) : runs.data.length === 0 ? (
        <StateMessage title="No runs found.">
          Change the filters, or run a workflow.
        </StateMessage>
      ) : (
        <>
          <TableFrame>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="px-4">Status</TableHead>
                  <TableHead className="px-4">Run</TableHead>
                  <TableHead className="px-4">Session</TableHead>
                  <TableHead className="px-4">Workflow</TableHead>
                  <TableHead className="px-4">Started</TableHead>
                  <TableHead className="px-4">Duration</TableHead>
                  <TableHead className="px-4">Ended</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {runs.data.map((run) => (
                  <TableRow key={run.executionId}>
                    <TableCell className="px-4">
                      <StatusPill status={run.status} />
                    </TableCell>
                    <TableCell className="text-code-sm px-4 py-3">
                      <Link
                        className="text-primary"
                        title={run.executionId}
                        to={`/sessions/${encodeURIComponent(run.sessionId)}/workflow/${encodeURIComponent(run.workflowId)}?exec=${encodeURIComponent(run.executionId)}`}
                      >
                        {run.executionId.slice(0, 8)}
                      </Link>
                      <button
                        type="button"
                        aria-label={`Copy run id ${run.executionId}`}
                        onClick={() =>
                          void navigator.clipboard.writeText(run.executionId)
                        }
                        className="material-symbols-outlined text-on-surface-variant ml-1 align-middle text-sm"
                      >
                        content_copy
                      </button>
                    </TableCell>
                    <TableCell className="px-4">{run.sessionId}</TableCell>
                    <TableCell className="px-4">{run.workflowId}</TableCell>
                    <TableCell className="text-code-sm px-4">
                      {formatClockTime(run.startTime)}
                    </TableCell>
                    <TableCell className="text-code-sm px-4">
                      {formatDuration(run.startTime, run.endTime)}
                    </TableCell>
                    <TableCell className="text-code-sm px-4">
                      {formatClockTime(run.endTime)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableFrame>
          {runs.data.length >= limit && limit < MAX_RUNS && (
            <div>
              <Button
                type="button"
                variant="outline"
                onClick={() => setLimit((n) => Math.min(n + PAGE, MAX_RUNS))}
              >
                Load more
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
