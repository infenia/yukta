// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { cn } from '@/lib/utils';
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
import { RecoverButton } from '@/features/workflow/components/RecoverButton';
import { ControlEventsProvider } from '@/features/control/context/ControlEvents';
import { useControlEvents } from '@/features/control/context/ControlEventsContext';
import { runPath } from '../lib/runPath';
import { StatusPill } from '@/features/workflow/components/StatusPill';
import {
  isAbortedExecStatus,
  toStatusDisplay,
} from '@/features/workflow/lib/statusMapper';
import {
  formatClockTime,
  formatDuration,
} from '@/features/workflow/lib/timeFormat';
import {
  MAX_RUNS,
  getRunCounts,
  searchExecutions,
  type RunCounts,
} from '../api/searchExecutions';
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

/** How the footer names the time range of the runs it counts. */
const RANGE_PHRASE: Record<Range, string> = {
  '': '',
  '1h': ' in the last hour',
  '24h': ' in the last 24 hours',
  '7d': ' in the last 7 days',
  custom: ' in this time range',
};

/** Runs of every session, newest first, narrowed by filters that live in the URL. */
/** Phones: each run stacks into a card of labelled values (the label comes from data-label). */
const STACKED_CELL =
  'px-4 max-sm:flex max-sm:items-center max-sm:gap-3 max-sm:py-0.5 max-sm:whitespace-normal max-sm:before:w-20 max-sm:before:shrink-0 max-sm:before:text-body-sm max-sm:before:text-muted-foreground max-sm:before:content-[attr(data-label)]';

/** The Runs page, kept up to date by the event stream. */
export function RunsPage() {
  return (
    <ControlEventsProvider>
      <Runs />
    </ControlEventsProvider>
  );
}

function Runs() {
  const { connected } = useControlEvents();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const filters = parseFilters(params);
  const [limit, setLimit] = useState(PAGE);
  const sessions = useSessionSummaries();
  const sessionNames = new Map(
    sessions.data?.map((session) => [session.sessionId, session.name])
  );
  const runs = useQuery({
    queryKey: ['runs', filters, limit],
    queryFn: () => searchExecutions(filters, limit),
    placeholderData: (previous) => previous,
  });
  const workflows = useSessionWorkflows(filters.session);
  const counts = useQuery({
    queryKey: ['runs', 'counts'],
    queryFn: getRunCounts,
  });
  // Each chip is a filter: the same status (and, for failures, the last 24 hours)
  const chips: [keyof RunCounts, string, Partial<RunFilters>][] = [
    ['running', 'running', { status: 'RUNNING', range: '' }],
    ['queued', 'queued', { status: 'QUEUED', range: '' }],
    ['failed', 'failed in 24 h', { status: 'FAILURE', range: '24h' }],
  ];

  const set = (change: Partial<RunFilters>) => {
    setParams(toParams({ ...filters, ...change }), { replace: true });
    setLimit(PAGE);
  };

  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        backdrop
        title="Runs"
        description="Runs of every session, newest first."
      />
      {counts.data && (
        <div
          role="group"
          aria-label="Run counts"
          className="flex flex-wrap gap-2"
        >
          {chips.map(([key, label, change]) => {
            const active =
              filters.status === change.status &&
              filters.range === change.range;
            return (
              <button
                key={key}
                type="button"
                aria-pressed={active}
                onClick={() => set(active ? { status: '', range: '' } : change)}
                className={cn(
                  'inline-flex h-8 items-center gap-1.5 rounded-full border px-3 text-label-md',
                  active
                    ? 'border-transparent bg-accent text-accent-foreground'
                    : 'border-border text-on-surface hover:bg-muted'
                )}
              >
                <span className="font-semibold tabular-nums">
                  {counts.data[key]}
                </span>{' '}
                {label}
              </button>
            );
          })}
        </div>
      )}
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
            <Table className="max-sm:block [&_tbody]:max-sm:block">
              <TableHeader className="max-sm:hidden">
                <TableRow>
                  <TableHead className="px-4">Status</TableHead>
                  <TableHead className="px-4">Run</TableHead>
                  <TableHead className="px-4">Session / workflow</TableHead>
                  <TableHead className="px-4">Progress</TableHead>
                  <TableHead className="px-4">Started</TableHead>
                  <TableHead className="px-4">Duration</TableHead>
                  <TableHead className="px-4">Initiator</TableHead>
                  <TableHead className="px-4">
                    <span className="sr-only">Actions</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {runs.data.map((run) => (
                  <TableRow
                    key={run.executionId}
                    className="max-sm:flex max-sm:flex-col max-sm:gap-1 max-sm:py-3"
                  >
                    <TableCell data-label="Status" className={STACKED_CELL}>
                      <StatusPill status={run.status} />
                    </TableCell>
                    <TableCell
                      data-label="Run"
                      className={cn(STACKED_CELL, 'py-3 text-code-sm')}
                    >
                      <Link
                        className="text-primary"
                        title={run.executionId}
                        to={runPath(
                          run.sessionId,
                          run.workflowId,
                          run.executionId
                        )}
                      >
                        {run.executionId.slice(0, 8)}
                      </Link>
                      <button
                        type="button"
                        aria-label={`Copy run id ${run.executionId}`}
                        onClick={() =>
                          void navigator.clipboard.writeText(run.executionId)
                        }
                        className="material-symbols-outlined ml-1 align-middle text-sm text-on-surface-variant"
                      >
                        content_copy
                      </button>
                    </TableCell>
                    <TableCell
                      data-label="Session / workflow"
                      className={STACKED_CELL}
                    >
                      {sessionNames.get(run.sessionId) || run.sessionId}
                      <span className="text-on-surface-variant"> / </span>
                      <span className="text-code-sm">{run.workflowId}</span>
                    </TableCell>
                    <TableCell
                      data-label="Progress"
                      className={cn(STACKED_CELL, 'text-code-sm')}
                    >
                      {run.nodes ? (
                        <>
                          <span
                            role="progressbar"
                            aria-label={`Progress of run ${run.executionId.slice(0, 8)}`}
                            aria-valuemin={0}
                            aria-valuemax={run.nodes.total}
                            aria-valuenow={run.nodes.done}
                            className="mr-2 inline-block h-1 w-16 overflow-hidden rounded-full bg-muted align-middle"
                          >
                            <span
                              className={cn(
                                'block h-full rounded-full',
                                run.nodes.failed ? 'bg-error' : 'bg-primary'
                              )}
                              style={{
                                width: `${
                                  run.nodes.total > 0
                                    ? Math.min(
                                        100,
                                        (run.nodes.done / run.nodes.total) * 100
                                      )
                                    : 0
                                }%`,
                              }}
                            />
                          </span>
                          {run.nodes.done}/{run.nodes.total}
                          {run.nodes.failed && (
                            <span className="ml-2 text-error">
                              failed at {run.nodes.failed}
                            </span>
                          )}
                        </>
                      ) : (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </TableCell>
                    <TableCell
                      data-label="Started"
                      className={cn(STACKED_CELL, 'text-code-sm')}
                    >
                      {formatClockTime(run.startTime)}
                    </TableCell>
                    <TableCell
                      data-label="Duration"
                      className={cn(STACKED_CELL, 'text-code-sm')}
                    >
                      {formatDuration(run.startTime, run.endTime)}
                    </TableCell>
                    <TableCell
                      data-label="Initiator"
                      className={cn(STACKED_CELL, 'text-code-sm')}
                    >
                      {run.initiator || (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </TableCell>
                    <TableCell className="px-4 max-sm:empty:hidden">
                      {isAbortedExecStatus(run.status) && (
                        <RecoverButton
                          size="sm"
                          sessionId={run.sessionId}
                          workflowId={run.workflowId}
                          executionId={run.executionId}
                          onRecovered={(executionId) =>
                            void navigate(
                              runPath(
                                run.sessionId,
                                run.workflowId,
                                executionId
                              )
                            )
                          }
                        />
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableFrame>
          <div className="flex flex-wrap items-center gap-3 text-body-sm text-on-surface-variant">
            <span>
              {runs.data.length} run{runs.data.length === 1 ? '' : 's'}
              {RANGE_PHRASE[filters.range]}
            </span>
            {runs.data.length >= limit && limit < MAX_RUNS && (
              <Button
                type="button"
                variant="outline"
                onClick={() => setLimit((n) => Math.min(n + PAGE, MAX_RUNS))}
              >
                Load more
              </Button>
            )}
            <span className="ml-auto flex flex-wrap items-center gap-x-3">
              {runs.data.length >= MAX_RUNS && (
                <span>
                  Showing the newest {MAX_RUNS} · narrow the filters to see
                  older runs
                </span>
              )}
              {connected && (
                <span data-testid="runs-live">
                  Updates live · filters are kept in the URL
                </span>
              )}
            </span>
          </div>
        </>
      )}
    </div>
  );
}
