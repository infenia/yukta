// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useState } from 'react';
import { toNodeUiStatus } from '../lib/statusMapper';
import { StatusPill } from './StatusPill';
import { formatClockTime, formatDuration } from '../lib/timeFormat';
import { cn } from '@/lib/utils';
import { NodeControlsMenu, type NodeCommand } from './NodeControlsMenu';
import type {
  NodeUiStatus,
  TaskProgress,
  WorkflowGraph,
} from '../types/workflow';

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
    <div data-testid="task-sequence">
      <h3 className="text-overline mb-2">Task sequence</h3>
      <ul className="flex flex-col">
        {tasks.map((task) => (
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
