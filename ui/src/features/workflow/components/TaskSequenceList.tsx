// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useState, type ReactNode } from 'react';
import { toNodeStatusLabel, toNodeUiStatus } from '../lib/statusMapper';
import { StatusPill } from './StatusPill';
import { formatClockTime, formatDuration } from '../lib/timeFormat';
import { cn } from '@/lib/utils';
import { NodeControlsMenu, type NodeCommand } from './NodeControlsMenu';
import { aggregateStatus, groupNodes, stageOf } from '../lib/groups';
import { BAR_COLOR } from '../lib/timeline';
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

/** A raw status for each bucket, so a stage's aggregate reads like a node's in StatusPill. */
const RAW_STATUS: Record<NodeUiStatus, string> = {
  failed: 'FAILURE',
  running: 'RUNNING',
  paused: 'PAUSED',
  completed: 'SUCCESS',
  skipped: 'SKIPPED',
  pending: 'PENDING',
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

/** A stage's tasks under one collapsible heading with its aggregate status. */
function StageSection({
  stage,
  tasks,
  total,
  openByDefault,
  children,
}: {
  stage: string;
  tasks: TaskProgress[];
  total: number;
  openByDefault: boolean;
  children: ReactNode;
}) {
  const status = aggregateStatus(
    tasks.map((task) => toNodeUiStatus(task.status))
  );
  return (
    <li data-testid={`task-stage-${stage}`}>
      <details
        open={openByDefault || status === 'failed' || status === 'running'}
        className="group/stage"
      >
        <summary className="hover:bg-muted flex cursor-pointer items-center gap-2 rounded-lg px-1 py-2">
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base transition-transform group-open/stage:rotate-90"
          >
            chevron_right
          </span>
          <span className="text-label-md text-on-surface">{stage}</span>
          <span className="text-body-sm text-muted-foreground tabular-nums">
            {tasks.length === total ? total : `${tasks.length} of ${total}`}
          </span>
          <StatusPill
            status={RAW_STATUS[status]}
            scope="node"
            testId="stage-status"
            className="ml-auto"
          />
        </summary>
        <ul className="ml-3 flex flex-col">{children}</ul>
      </details>
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
  // Each stage's shown tasks sit together where its first one would; a lone node stays a row
  const stages = groupNodes(graph.nodes);
  const stageSizes = new Map(
    [...stages].map(([stage, members]) => [stage, members.length])
  );
  const nodeById = new Map(graph.nodes.map((node) => [node.nodeId, node]));
  const sections: { stage: string | null; tasks: TaskProgress[] }[] = [];
  const sectionOf = new Map<string, TaskProgress[]>();
  for (const task of shown) {
    const node = nodeById.get(task.nodeId);
    const stage = node && stages.has(stageOf(node)) ? stageOf(node) : null;
    const section = stage === null ? undefined : sectionOf.get(stage);
    if (section) {
      section.push(task);
    } else {
      const created = [task];
      if (stage !== null) sectionOf.set(stage, created);
      sections.push({ stage, tasks: created });
    }
  }
  const filtering = show !== 'all' || needle !== '';
  const item = (task: TaskProgress) => (
    <TaskSequenceItem
      key={task.nodeId}
      task={task}
      onNodeCommand={onNodeCommand}
      scope={scope}
    />
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
        {sections.map(({ stage, tasks: members }) =>
          stage === null ? (
            item(members[0])
          ) : (
            <StageSection
              key={`${stage}/${filtering}`}
              stage={stage}
              tasks={members}
              total={stageSizes.get(stage) as number}
              openByDefault={filtering}
            >
              {members.map(item)}
            </StageSection>
          )
        )}
      </ul>
    </div>
  );
}
