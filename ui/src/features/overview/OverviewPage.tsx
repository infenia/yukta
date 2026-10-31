// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { RecoverButton } from '@/features/workflow/components/RecoverButton';
import { Panel, Tile } from '@/components/dashboard/Tile';
import { PageHeader } from '@/components/layout/PageHeader';
import { getLimits } from '@/features/control/api/scheduler';
import { useWorkers } from '@/features/control/api/workers';
import { ControlEventsProvider } from '@/features/control/context/ControlEvents';
import {
  useControlEvents,
  useFallbackPoll,
} from '@/features/control/context/ControlEventsContext';
import { describe } from '@/features/control/lib/activity';
import {
  getRunCounts,
  searchExecutions,
} from '@/features/runs/api/searchExecutions';
import { runPath } from '@/features/runs/lib/runPath';
import { StatusPill } from '@/features/workflow/components/StatusPill';
import { ApprovalRow } from '@/features/workflow/components/ApprovalRow';
import { getAllApprovals } from './api';

const NO_FILTERS = {
  session: '',
  workflow: '',
  status: '',
  text: '',
  range: '' as const,
  since: '',
  until: '',
};

const Empty = ({ children }: { children: ReactNode }) => (
  <p className="text-body-sm text-on-surface-variant">{children}</p>
);

/** Pending approvals of every session, answered in place, and aborted runs to recover. */
function NeedsAttention({ poll }: { poll: number | false }) {
  const navigate = useNavigate();
  const approvals = useQuery({
    queryKey: ['overview', 'approvals'],
    queryFn: () => getAllApprovals(),
    refetchInterval: poll,
  });
  const aborted = useQuery({
    queryKey: ['overview', 'aborted'],
    queryFn: () => searchExecutions({ ...NO_FILTERS, status: 'ABORTED' }, 5),
    refetchInterval: poll,
  });
  const waiting = approvals.data ?? [];
  const stopped = aborted.data ?? [];
  return (
    <Panel title="Needs attention">
      {waiting.length === 0 && stopped.length === 0 && (
        <Empty>Nothing is waiting for you.</Empty>
      )}
      <ul className="flex flex-col gap-2">
        {waiting.map((approval) => (
          <li
            key={`${approval.executionId}-${approval.nodeId}-${approval.name}`}
          >
            <ApprovalRow
              approval={approval}
              queryKey={['overview', 'approvals']}
              label={
                <>
                  <Link
                    className="text-primary"
                    to={runPath(
                      approval.sessionId,
                      approval.workflowId,
                      approval.executionId
                    )}
                  >
                    {approval.sessionId}/{approval.workflowId}
                  </Link>{' '}
                  <span className="font-mono">{approval.nodeId}</span> waits for
                  “{approval.name}”
                </>
              }
            />
          </li>
        ))}
        {stopped.map((run) => (
          <li
            key={run.executionId}
            className="flex flex-wrap items-center gap-2"
          >
            <StatusPill status={run.status} />
            <Link
              className="flex-1 text-body-md text-primary"
              to={runPath(run.sessionId, run.workflowId, run.executionId)}
            >
              {run.sessionId}/{run.workflowId}
            </Link>
            <RecoverButton
              size="sm"
              sessionId={run.sessionId}
              workflowId={run.workflowId}
              executionId={run.executionId}
              onRecovered={(executionId) =>
                void navigate(
                  runPath(run.sessionId, run.workflowId, executionId)
                )
              }
            />
          </li>
        ))}
      </ul>
    </Panel>
  );
}

function RecentFailures({ poll }: { poll: number | false }) {
  const failures = useQuery({
    queryKey: ['overview', 'failures'],
    queryFn: () =>
      searchExecutions({ ...NO_FILTERS, status: 'FAILURE', range: '24h' }, 10),
    refetchInterval: poll,
  });
  return (
    <Panel
      title="Recent failures"
      action={
        <Link
          className="text-label-md text-primary"
          to="/runs?status=FAILURE&range=24h"
        >
          See all
        </Link>
      }
    >
      {failures.data?.length === 0 && <Empty>No failures in 24 hours.</Empty>}
      <ul className="flex flex-col gap-1">
        {failures.data?.map((run) => (
          <li key={run.executionId} className="flex items-center gap-2">
            <StatusPill status={run.status} />
            <Link
              className="truncate text-body-md text-primary"
              to={runPath(run.sessionId, run.workflowId, run.executionId)}
            >
              {run.sessionId}/{run.workflowId}
            </Link>
          </li>
        ))}
      </ul>
    </Panel>
  );
}

function LiveActivity() {
  const { connected, events } = useControlEvents();
  const last = events.slice(-20).reverse();
  return (
    <Panel
      title="Live activity"
      action={
        <Link className="text-label-md text-primary" to="/control?tab=activity">
          Open activity
        </Link>
      }
    >
      {!connected && <Empty>Connecting to the event stream…</Empty>}
      {connected && last.length === 0 && <Empty>Waiting for events.</Empty>}
      <ul className="flex flex-col gap-1">
        {last.map((entry) => (
          <li key={entry.seq} className="truncate text-body-sm text-on-surface">
            {describe(entry)}
          </li>
        ))}
      </ul>
    </Panel>
  );
}

function Tiles({ poll }: { poll: number | false }) {
  const counts = useQuery({
    queryKey: ['overview', 'counts'],
    queryFn: getRunCounts,
    refetchInterval: poll,
  });
  const limits = useQuery({
    queryKey: ['control', 'scheduler'],
    queryFn: getLimits,
  });
  const approvals = useQuery({
    queryKey: ['overview', 'approvals'],
    queryFn: () => getAllApprovals(),
    refetchInterval: poll,
  });
  const workers = useWorkers();
  const dash = '–';
  const max = limits.data?.maxConcurrent;
  return (
    <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
      <Tile
        label="Running"
        value={counts.data?.running ?? dash}
        to="/runs?status=RUNNING"
        hint={max ? `limit ${max}` : undefined}
      />
      <Tile
        label="Queued"
        value={counts.data?.queued ?? dash}
        to="/runs?status=QUEUED"
        hint={
          limits.data?.queueCapacity
            ? `of ${limits.data.queueCapacity}`
            : undefined
        }
      />
      <Tile
        label="Failed in 24 h"
        value={counts.data?.failed ?? dash}
        to="/runs?status=FAILURE&range=24h"
      />
      <Tile
        label="Pending approvals"
        value={approvals.data?.length ?? dash}
        to="#ov-needs-attention-panel"
      />
      <Tile
        label="Workers online"
        value={workers.data?.length ?? dash}
        to="/control"
      />
    </div>
  );
}

function Overview() {
  const poll = useFallbackPoll();
  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        backdrop
        title="Overview"
        description="What is running, what failed and what needs you, across all sessions."
      />
      <Tiles poll={poll} />
      <div className="grid gap-4 lg:grid-cols-2">
        <NeedsAttention poll={poll} />
        <RecentFailures poll={poll} />
      </div>
      <LiveActivity />
    </div>
  );
}

/** The home page: tiles, approvals to answer, recent failures and the live feed. */
export function OverviewPage() {
  return (
    <ControlEventsProvider>
      <Overview />
    </ControlEventsProvider>
  );
}
