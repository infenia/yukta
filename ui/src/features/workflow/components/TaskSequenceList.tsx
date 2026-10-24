// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useState } from 'react';
import { toNodeUiStatus, toTaskSequenceLabel } from '../lib/statusMapper';
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

const STATUS_ICONS: Record<NodeUiStatus, string> = {
  completed: 'check_circle',
  running: 'sync',
  pending: 'schedule',
  failed: 'error',
  paused: 'pause_circle',
  skipped: 'skip_next',
};

const LABEL_CLASSES: Record<NodeUiStatus, string> = {
  completed: 'text-success',
  running: 'text-primary',
  pending: 'text-on-surface-variant',
  failed: 'text-error',
  paused: 'text-on-surface-variant',
  skipped: 'text-on-surface-variant',
};

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
      <p className="text-on-surface-variant text-code-sm">
        Start: {formatClockTime(task.startTime)}
        {'  '}Dur: {formatDuration(task.startTime, new Date().toISOString())}
      </p>
    );
  }
  return (
    <p className="text-on-surface-variant text-code-sm">
      Start: {formatClockTime(task.startTime)}
      {'  '}End: {formatClockTime(task.endTime)}
    </p>
  );
}

function TaskSequenceItem({
  task,
  onNodeCommand,
}: {
  task: TaskProgress;
  onNodeCommand?: (nodeId: string, command: NodeCommand) => void;
}) {
  const status = toNodeUiStatus(task.status);
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
        <span
          className={cn(
            'material-symbols-outlined text-base',
            status === 'running' && 'text-primary animate-spin',
            status === 'completed' && 'text-success',
            status === 'failed' && 'text-error'
          )}
        >
          {STATUS_ICONS[status]}
        </span>
        <span className="text-on-surface text-label-md">{task.nodeId}</span>
        <span
          className={cn(
            'text-label-sm ml-auto font-semibold tracking-wider',
            LABEL_CLASSES[status]
          )}
        >
          {toTaskSequenceLabel(status)}
        </span>
        {onNodeCommand && (
          <NodeControlsMenu
            nodeId={task.nodeId}
            status={status}
            onCommand={onNodeCommand}
          />
        )}
      </div>
      <p className="text-on-surface-variant text-body-sm pl-6">
        module: {task.module}
      </p>
      <div className="pl-6">
        <TimingLine task={task} status={status} />
      </div>
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
}: {
  graph: WorkflowGraph;
  tasksByNode: Record<string, TaskProgress | undefined>;
  onNodeCommand?: (nodeId: string, command: NodeCommand) => void;
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
          />
        ))}
      </ul>
    </div>
  );
}
