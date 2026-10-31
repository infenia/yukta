// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { RequirePermission } from '@/components/common/RequirePermission';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { relativeTime } from '@/features/control/components/WorkersPanel.helpers';
import { RecoverButton } from '@/features/workflow/components/RecoverButton';
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
import { sendDecision } from '@/features/workflow/api/approvals';
import { controlErrorMessage } from '@/features/workflow/hooks/useExecutionControl';
import { StatusPill } from '@/features/workflow/components/StatusPill';
import { getAllApprovals, type SessionApproval } from './api';

const NO_FILTERS = {
  session: '',
  workflow: '',
  status: '',
  text: '',
  range: '' as const,
  since: '',
  until: '',
};

function Tile({
  label,
  value,
  to,
  hint,
}: {
  label: string;
  value: ReactNode;
  to: string;
  hint?: string;
}) {
  const body = (
    <>
      <span className="text-label-md text-on-surface-variant">{label}</span>
      <span className="text-headline-md text-on-surface">{value}</span>
      {hint && (
        <span className="text-body-sm text-on-surface-variant">{hint}</span>
      )}
    </>
  );
  const className =
    'border-border bg-card flex flex-col gap-1 rounded-xl border p-4 no-underline';
  // A tile that points into this page is a plain anchor, so the browser scrolls to it
  return to.startsWith('#') ? (
    <a href={to} className={className}>
      {body}
    </a>
  ) : (
    <Link to={to} className={className}>
      {body}
    </Link>
  );
}

function Panel({
  title,
  action,
  children,
}: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  const id = `ov-${title.replace(/\W+/g, '-').toLowerCase()}`;
  return (
    <section
      id={`${id}-panel`}
      aria-labelledby={id}
      className="border-border bg-card flex min-w-0 scroll-mt-20 flex-col gap-2 rounded-xl border p-4"
    >
      <div className="flex items-center justify-between gap-2">
        <h2 id={id} className="text-label-md text-on-surface">
          {title}
        </h2>
        {action}
      </div>
      {children}
    </section>
  );
}

const Empty = ({ children }: { children: ReactNode }) => (
  <p className="text-body-sm text-on-surface-variant">{children}</p>
);

/** One approval: Approve at once, Reject after a confirm with an optional reason. */
function ApprovalRow({ approval }: { approval: SessionApproval }) {
  const queryClient = useQueryClient();
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const decide = useMutation({
    mutationFn: (decision: 'approve' | 'reject') =>
      sendDecision(
        approval,
        decision,
        decision === 'reject' ? reason : undefined
      ),
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: ['overview', 'approvals'] }),
  });
  const alreadyAnswered =
    (decide.error as { status?: number } | null)?.status === 409;
  return (
    <li className="flex flex-wrap items-center gap-2">
      <span className="text-body-md text-on-surface min-w-0 flex-1">
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
        <span className="font-mono">{approval.nodeId}</span> waits for “
        {approval.name}”{' '}
        <span className="text-on-surface-variant">
          since {relativeTime(approval.since).replace(/^in /, '')}
        </span>
      </span>
      <RequirePermission perm="controlRuns">
        <Button
          size="sm"
          disabled={decide.isPending}
          onClick={() => decide.mutate('approve')}
        >
          Approve
        </Button>
      </RequirePermission>
      <RequirePermission perm="controlRuns">
        <Button
          size="sm"
          variant="outline"
          disabled={decide.isPending}
          onClick={() => setRejecting(true)}
        >
          Reject
        </Button>
      </RequirePermission>
      {decide.error && (
        <div className="basis-full">
          <InlineError
            message={
              alreadyAnswered
                ? 'Already answered.'
                : controlErrorMessage(decide.error)
            }
          />
        </div>
      )}
      {rejecting && (
        <Modal
          title="Reject this approval?"
          onClose={() => setRejecting(false)}
        >
          <label
            htmlFor="reject-reason"
            className="text-label-md text-on-surface"
          >
            Reason (optional)
          </label>
          <Input
            id="reject-reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
          <div className="mt-4 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setRejecting(false)}>
              Cancel
            </Button>
            <Button
              onClick={() => {
                setRejecting(false);
                decide.mutate('reject');
              }}
            >
              Reject
            </Button>
          </div>
        </Modal>
      )}
    </li>
  );
}

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
          <ApprovalRow
            key={`${approval.executionId}-${approval.nodeId}-${approval.name}`}
            approval={approval}
          />
        ))}
        {stopped.map((run) => (
          <li
            key={run.executionId}
            className="flex flex-wrap items-center gap-2"
          >
            <StatusPill status={run.status} />
            <Link
              className="text-primary text-body-md flex-1"
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
          className="text-primary text-label-md"
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
              className="text-primary text-body-md truncate"
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
        <Link className="text-primary text-label-md" to="/control?tab=activity">
          Open activity
        </Link>
      }
    >
      {!connected && <Empty>Connecting to the event stream…</Empty>}
      {connected && last.length === 0 && <Empty>Waiting for events.</Empty>}
      <ul className="flex flex-col gap-1">
        {last.map((entry) => (
          <li key={entry.seq} className="text-body-sm text-on-surface truncate">
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
