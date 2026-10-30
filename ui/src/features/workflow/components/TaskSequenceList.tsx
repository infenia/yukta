// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useState } from 'react';
import { toNodeStatusLabel, toNodeUiStatus } from '../lib/statusMapper';
import { StatusPill } from './StatusPill';
import { formatClockTime, formatDuration } from '../lib/timeFormat';
import { cn } from '@/lib/utils';
import { NodeControlsMenu, type NodeCommand } from './NodeControlsMenu';
import type {
  NodeUiStatus,
  TaskProgress,
  WorkflowGraph,
} from '../types/workflow';

/** Filter chips and summary-bar segments, in the order a run moves through them. */
const STATUS_ORDER: NodeUiStatus[] = [
  'failed',
  'running',
  'paused',
  'completed',
  'skipped',
  'pending',
];

const BAR_COLOR: Record<NodeUiStatus, string> = {
  failed: 'bg-destructive',
  running: 'bg-primary',
  paused: 'bg-attention',
  completed: 'bg-success',
  skipped: 'bg-outline',
  pending: 'bg-transparent',
};

/** How often the running-duration display refreshes between SSE updates. */
const DURATION_REFRESH_MS = 1000;

function TimingLine({
  task,
  status,
}: {
  task: TaskProgress;
  status: NodeUiStatus;
}) {
  if (!task.startTime) {
    return null;
  }
  if (status === 'running') {
    return (
      <p className="text-code-sm text-on-surface-variant">
        Start: {formatClockTime(task.startTime)}
        {'  '}Dur: {formatDuration(task.startTime, new Date().toISOString())}
      </p>
    );
  }
  return (
    <p className="text-code-sm text-on-surface-variant">
      Start: {formatClockTime(task.startTime)}
      {'  '}End: {formatClockTime(task.endTime)}
    </p>
  );
}

function TaskSequenceItem({
  task,
  onNodeCommand,
  scope,
}: {
  task: TaskProgress;
  onNodeCommand?: (nodeId: string, command: NodeCommand) => void;
  scope?: { sessionId: string; executionId: string };
}) {
  const status = toNodeUiStatus(task.status);
  const breakpoint = task.breakpoint ?? undefined;
  const dimmed = status === 'pending' || status === 'skipped';
  return (
    <li
      className={cn(
        'border-l-2 py-2 pl-4',
        dimmed ? 'border-outline-variant opacity-60' : 'border-primary'
      )}
      data-testid={`task-item-${task.nodeId}`}
    >
      <div className="flex items-center gap-2">
        <span className="text-label-md text-on-surface">{task.nodeId}</span>
        {breakpoint && (
          <span
            className="material-symbols-outlined text-error text-base"
            title={`${breakpoint.field} = ${breakpoint.equals}`}
            aria-label={`Breakpoint: ${breakpoint.field} = ${breakpoint.equals}`}
            role="img"
          >
            radio_button_checked
          </span>
        )}
        <StatusPill
          status={task.status}
          scope="node"
          testId="task-status"
          className="ml-auto"
        />
        {onNodeCommand && (
          <NodeControlsMenu
            nodeId={task.nodeId}
            status={status}
            onCommand={onNodeCommand}
            hasBreakpoint={breakpoint !== undefined}
            scope={scope}
          />
        )}
      </div>
      <p className="text-body-sm text-on-surface-variant">
        module: {task.module}
      </p>
      <TimingLine task={task} status={status} />
    </li>
  );
}

/**
 * The TASK SEQUENCE timeline: one entry per node in topological order, showing live
 * status, module and timing. Falls back to all-pending entries before the first run.
 */
export function TaskSequenceList({
  graph,
  tasksByNode,
  onNodeCommand,
  scope,
}: {
  graph: WorkflowGraph;
  tasksByNode: Record<string, TaskProgress | undefined>;
  onNodeCommand?: (nodeId: string, command: NodeCommand) => void;
  /** Session and run of the tasks, for the "Copy as" commands. */
  scope?: { sessionId: string; executionId: string };
}) {
  const order =
    graph.topologicalOrder.length > 0
      ? graph.topologicalOrder
      : graph.nodes.map((node) => node.nodeId);
  const tasks: TaskProgress[] = order.map(
    (nodeId) =>
      tasksByNode[nodeId] ?? {
        nodeId,
        module:
          graph.nodes.find((node) => node.nodeId === nodeId)?.type ?? 'unknown',
        status: 'PENDING',
        startTime: null,
        endTime: null,
      }
  );

  const hasRunningTask = tasks.some(
    (task) => toNodeUiStatus(task.status) === 'running'
  );
  const [show, setShow] = useState<NodeUiStatus | 'all'>('all');
  const [query, setQuery] = useState('');
  const counts = new Map<NodeUiStatus, number>();
  for (const task of tasks) {
    const status = toNodeUiStatus(task.status);
    counts.set(status, (counts.get(status) ?? 0) + 1);
  }
  const needle = query.trim().toLowerCase();
  const shown = tasks.filter(
    (task) =>
      (show === 'all' || toNodeUiStatus(task.status) === show) &&
      `${task.nodeId} ${task.module}`.toLowerCase().includes(needle)
  );
  const chip = (active: boolean) =>
    `text-label-sm inline-flex h-6 items-center gap-1 rounded-full border px-2 tabular-nums ${
      active
        ? 'border-transparent bg-accent text-accent-foreground'
        : 'border-border text-on-surface hover:bg-muted'
    }`;

  // Re-render on a fixed interval so running durations keep advancing between SSE updates;
  // the interval only exists while a task is actually running.
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!hasRunningTask) {
      return undefined;
    }
    const id = setInterval(
      () => setTick((value) => value + 1),
      DURATION_REFRESH_MS
    );
    return () => clearInterval(id);
  }, [hasRunningTask]);

  return (
    <div data-testid="task-sequence" className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between">
        <h3 className="text-overline">Task sequence</h3>
        <span className="text-body-sm text-muted-foreground tabular-nums">
          {tasks.length} nodes
        </span>
      </div>
      <div
        role="img"
        aria-label={STATUS_ORDER.filter((status) => counts.has(status))
          .map((status) => `${counts.get(status)} ${toNodeStatusLabel(status)}`)
          .join(', ')}
        className="bg-surface-container-high flex h-2 overflow-hidden rounded-full"
      >
        {STATUS_ORDER.map((status) => (
          <span
            key={status}
            className={BAR_COLOR[status]}
            style={{
              width: `${((counts.get(status) ?? 0) / Math.max(tasks.length, 1)) * 100}%`,
            }}
          />
        ))}
      </div>
      <div
        role="group"
        aria-label="Show nodes"
        className="flex flex-wrap gap-1.5"
      >
        <button
          type="button"
          aria-pressed={show === 'all'}
          onClick={() => setShow('all')}
          className={chip(show === 'all')}
        >
          All {tasks.length}
        </button>
        {STATUS_ORDER.filter((status) => counts.has(status)).map((status) => (
          <button
            key={status}
            type="button"
            aria-pressed={show === status}
            onClick={() => setShow(status)}
            className={chip(show === status)}
          >
            {toNodeStatusLabel(status)} {counts.get(status)}
          </button>
        ))}
      </div>
      <input
        type="search"
        aria-label="Filter nodes"
        placeholder="Filter nodes"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        className="bg-muted text-body-md placeholder:text-muted-foreground focus-visible:ring-ring/40 h-8 rounded-full px-3 outline-none focus-visible:ring-2"
      />
      {shown.length === 0 && (
        <p className="text-body-sm text-muted-foreground">No nodes match.</p>
      )}
      <ul className="flex flex-col">
        {shown.map((task) => (
          <TaskSequenceItem
            key={task.nodeId}
            task={task}
            onNodeCommand={onNodeCommand}
            scope={scope}
          />
        ))}
      </ul>
    </div>
  );
}
