// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  Fragment,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { Link } from 'react-router';
import { StatusPill } from './StatusPill';
import { BAR_COLOR, buildTimeline, type TimelineRow } from '../lib/timeline';
import { aggregateStatus, groupNodes } from '../lib/groups';
import { SubWorkflowLinks } from '../hooks/useSubWorkflowLinks';
import { formatClockTime, formatDuration } from '../lib/timeFormat';
import { cn } from '@/lib/utils';
import type {
  NodeUiStatus,
  TaskProgress,
  WorkflowGraph,
} from '../types/workflow';
import { Tip } from '@/components/ui/tooltip';

/** A stage's aggregate as the raw status the pill shows. */
const STAGE_RAW: Record<NodeUiStatus, string> = {
  completed: 'COMPLETED',
  running: 'RUNNING',
  pending: 'PENDING',
  failed: 'FAILED',
  paused: 'PAUSED',
  skipped: 'SKIPPED',
};

/** How often a running node's bar grows between status updates. */
const TICK_MS = 1000;

export type RunView = 'graph' | 'timeline';

/** Graph or timeline: the two ways to look at the run. */
export function ViewSwitch({
  view,
  onChange,
  className,
}: {
  view: RunView;
  onChange: (view: RunView) => void;
  className?: string;
}) {
  return (
    <div
      role="radiogroup"
      aria-label="Run view"
      className={cn(
        'border-border bg-surface shadow-card flex gap-0.5 rounded-lg border p-0.5',
        className
      )}
    >
      {(
        [
          ['graph', 'Graph'],
          ['timeline', 'Timeline'],
        ] as const
      ).map(([value, label]) => (
        <button
          key={value}
          type="button"
          role="radio"
          aria-checked={view === value}
          onClick={() => onChange(value)}
          className={cn(
            'text-label-sm rounded-md px-2.5 py-1',
            view === value
              ? 'bg-muted text-on-surface'
              : 'text-on-surface-variant hover:text-on-surface'
          )}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

/**
 * When each node ran and for how long, as bars on one time axis. It is a table, so a screen
 * reader gets node, status, start and duration per row; the bar column is only a picture.
 * Updates with the run: the status stream feeds `tasks`, and running bars grow each second.
 */
export function RunTimeline({
  tasks = [],
  graph,
  switcher,
}: {
  /** Absent until the run's status has arrived. */
  tasks?: TaskProgress[];
  /** The workflow, for each node's type, stage and sub-workflow; the rows work without it. */
  graph?: WorkflowGraph;
  switcher: ReactNode;
}) {
  const running = tasks.some((task) => task.startTime && !task.endTime);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!running) {
      return undefined;
    }
    const timer = setInterval(() => setNow(Date.now()), TICK_MS);
    return () => clearInterval(timer);
  }, [running]);
  const { rows, from, to } = useMemo(
    () => buildTimeline(tasks, now),
    [tasks, now]
  );
  const links = useContext(SubWorkflowLinks);
  const nodeById = useMemo(
    () => new Map((graph?.nodes ?? []).map((node) => [node.nodeId, node])),
    [graph]
  );
  // stages as the canvas groups them; a stage with one node started is still a stage
  const stageOf = useMemo(() => {
    const byNode = new Map<string, string>();
    groupNodes(graph?.nodes ?? []).forEach((members, stage) =>
      members.forEach((member) => byNode.set(member.nodeId, stage))
    );
    return byNode;
  }, [graph]);
  const sections = useMemo(() => {
    const out = new Map<string | null, TimelineRow[]>();
    rows.forEach((row) => {
      const stage = stageOf.get(row.nodeId) ?? null;
      out.set(stage, [...(out.get(stage) ?? []), row]);
    });
    return [...out];
  }, [rows, stageOf]);
  const [closed, setClosed] = useState<ReadonlySet<string>>(new Set());
  const toggle = (stage: string) =>
    setClosed((previous) => {
      const next = new Set(previous);
      if (!next.delete(stage)) next.add(stage);
      return next;
    });

  return (
    <div
      className="bg-background absolute inset-0 overflow-auto p-3"
      data-testid="run-timeline"
    >
      <div className="mb-3">{switcher}</div>
      {rows.length === 0 ? (
        <p className="text-body-sm text-on-surface-variant">
          No node has started yet.
        </p>
      ) : (
        <table className="w-full table-fixed border-collapse">
          <caption className="sr-only">When each node ran</caption>
          <thead>
            <tr className="text-overline text-on-surface-variant text-left">
              <th scope="col" className="w-48 pb-1 font-normal">
                Node
              </th>
              <th scope="col" className="w-16 pb-1 font-normal">
                Status
              </th>
              <th scope="col" className="w-20 pb-1 font-normal">
                Start
              </th>
              <th scope="col" className="w-20 pb-1 font-normal">
                Duration
              </th>
              <th scope="col" className="pb-1 font-normal">
                Timeline
              </th>
            </tr>
          </thead>
          <tbody>
            {sections.map(([stage, members]) => (
              <Fragment key={stage ?? '\0'}>
                {stage !== null && (
                  <StageRow
                    stage={stage}
                    members={members}
                    open={!closed.has(stage)}
                    onToggle={() => toggle(stage)}
                    from={from}
                    to={to}
                  />
                )}
                {(stage === null || !closed.has(stage)) &&
                  members.map((row) => (
                    <NodeRow
                      key={row.nodeId}
                      row={row}
                      type={nodeById.get(row.nodeId)?.type}
                      href={links.get(row.nodeId)}
                      indent={stage !== null}
                      from={from}
                      to={to}
                    />
                  ))}
              </Fragment>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

const clock = (ms: number) => formatClockTime(new Date(ms).toISOString());
const span = (from: number, to: number) =>
  formatDuration(new Date(from).toISOString(), new Date(to).toISOString());

/** The bar of one row on the shared axis; the tooltip says when it ran and how much of the run. */
function Bar({
  row,
  from,
  to,
  testId,
}: {
  row: Pick<TimelineRow, 'startMs' | 'endMs' | 'left' | 'width' | 'uiStatus'>;
  from: number;
  to: number;
  testId?: string;
}) {
  const share = Math.round(
    ((row.endMs - row.startMs) / Math.max(to - from, 1)) * 100
  );
  return (
    <Tip
      label={`${clock(row.startMs)} to ${clock(row.endMs)} · ${span(row.startMs, row.endMs)} · ${share}% of the run`}
    >
      <div className="bg-muted relative h-3 rounded-full" aria-hidden="true">
        <div
          data-testid={testId}
          className={cn(
            'absolute inset-y-0 rounded-full',
            BAR_COLOR[row.uiStatus]
          )}
          style={{ left: `${row.left}%`, width: `${row.width}%` }}
        />
      </div>
    </Tip>
  );
}

/** One node: name with its type, status, start, duration, bar, and the way into a sub-workflow. */
function NodeRow({
  row,
  type,
  href,
  indent,
  from,
  to,
}: {
  row: TimelineRow;
  type?: string;
  href?: string;
  indent: boolean;
  from: number;
  to: number;
}) {
  return (
    <tr className="border-border hover:bg-muted/60 border-t transition-colors">
      <th
        scope="row"
        className={cn(
          'py-1.5 pr-2 text-left font-normal',
          indent ? 'pl-6' : 'pl-1'
        )}
      >
        <Tip label={`${row.nodeId} · ${type ?? row.module}`}>
          <div className="flex min-w-0 items-center gap-1">
            <div className="min-w-0 flex-1">
              <div className="text-body-sm text-on-surface truncate">
                {row.nodeId}
              </div>
              <div className="text-code-sm text-on-surface-variant truncate">
                {type ?? row.module}
              </div>
            </div>
            {href && (
              <Tip label="Open the sub-workflow">
                <Link
                  to={href}
                  aria-label={`Open sub-workflow of ${row.nodeId}`}
                  className="text-primary hover:bg-surface inline-flex rounded p-0.5"
                >
                  <span
                    aria-hidden="true"
                    className="material-symbols-outlined text-lg"
                  >
                    account_tree
                  </span>
                </Link>
              </Tip>
            )}
          </div>
        </Tip>
      </th>
      <td className="py-1.5 pr-2">
        <StatusPill status={row.status} scope="node" iconOnly />
      </td>
      <td className="text-body-sm num text-on-surface-variant py-1.5 pr-2">
        {clock(row.startMs)}
      </td>
      <td className="text-body-sm num text-on-surface-variant py-1.5 pr-2">
        {span(row.startMs, row.endMs)}
      </td>
      <td className="py-1.5">
        <Bar
          row={row}
          from={from}
          to={to}
          testId={`timeline-bar-${row.nodeId}`}
        />
      </td>
    </tr>
  );
}

/** A stage's header row: toggle, name, count, its aggregate state, and one bar over its nodes. */
function StageRow({
  stage,
  members,
  open,
  onToggle,
  from,
  to,
}: {
  stage: string;
  members: TimelineRow[];
  open: boolean;
  onToggle: () => void;
  from: number;
  to: number;
}) {
  const startMs = Math.min(...members.map((m) => m.startMs));
  const endMs = Math.max(...members.map((m) => m.endMs));
  const total = Math.max(to - from, 1);
  const status = aggregateStatus(members.map((m) => m.uiStatus));
  const envelope = {
    startMs,
    endMs,
    left: ((startMs - from) / total) * 100,
    width: Math.max(((endMs - startMs) / total) * 100, 0.5),
    uiStatus: status,
  };
  return (
    <tr className="border-border bg-muted/30 hover:bg-muted/60 border-t transition-colors">
      <th scope="row" className="py-1.5 pr-2 pl-1 text-left font-normal">
        <button
          type="button"
          aria-expanded={open}
          aria-label={`${open ? 'Collapse' : 'Expand'} stage ${stage}`}
          onClick={onToggle}
          className="text-label-md text-on-surface flex w-full items-center gap-1"
        >
          <span
            aria-hidden="true"
            className={cn(
              'material-symbols-outlined text-on-surface-variant text-lg transition-transform',
              open && 'rotate-90'
            )}
          >
            chevron_right
          </span>
          <span className="truncate">{stage}</span>
          <span className="bg-foreground text-label-sm text-background min-w-5 rounded-full px-1 text-center tabular-nums">
            {members.length}
          </span>
        </button>
      </th>
      <td className="text-body-sm text-on-surface-variant py-1.5 pr-2">
        <StatusPill status={STAGE_RAW[status]} scope="node" iconOnly />
      </td>
      <td className="text-body-sm num text-on-surface-variant py-1.5 pr-2">
        {clock(startMs)}
      </td>
      <td className="text-body-sm num text-on-surface-variant py-1.5 pr-2">
        {span(startMs, endMs)}
      </td>
      <td className="py-1.5">
        <Bar row={envelope} from={from} to={to} />
      </td>
    </tr>
  );
}
