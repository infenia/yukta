// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Handle, Position } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import { toNodeStatusLabel } from '../../lib/statusMapper';
import { cn } from '@/lib/utils';
import { categoryAccent, categoryTint } from '../../lib/categoryAccent';
import { pluginIcon, pluginLabel } from '../../lib/pluginIcons';
import type { WorkflowFlowNode } from '../../lib/layout';
import type { NodeUiStatus, TaskProgress } from '../../types/workflow';

const STATUS_ICONS: Partial<Record<NodeUiStatus, string>> = {
  running: 'sync',
  failed: 'error',
  paused: 'pause_circle',
  skipped: 'skip_next',
};

const STATUS_CARD_CLASSES: Record<NodeUiStatus, string> = {
  completed: 'border-outline-variant',
  running: 'border-transparent',
  pending: 'border-outline-variant opacity-80',
  failed: 'border-error',
  paused: 'border-outline',
  skipped: 'border-outline-variant opacity-50',
};

const STATUS_DOT_CLASSES: Record<NodeUiStatus, string> = {
  completed: 'bg-success',
  running: 'live-dot bg-(--node-accent)',
  pending: 'border border-outline',
  failed: 'bg-error',
  paused: 'bg-warning',
  skipped: 'border border-outline',
};

function progressPercent(
  status: NodeUiStatus,
  task?: TaskProgress
): number | null {
  if (status !== 'running') {
    return null;
  }
  const raw = task?.metadata?.['progress'] ?? task?.metadata?.['percent'];
  return typeof raw === 'number' && Number.isFinite(raw)
    ? Math.min(100, Math.max(0, raw))
    : null;
}

function progressDetail(
  status: NodeUiStatus,
  task?: TaskProgress
): string | null {
  if (status !== 'running') {
    return null;
  }
  const metadata = task?.metadata ?? {};
  const raw = metadata['progress'] ?? metadata['percent'];
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return `${Math.round(raw)}%`;
  }
  return null;
}

function outputHandleTop(index: number, count: number): string {
  return `${((index + 1) / (count + 1)) * 100}%`;
}

const HANDLE_CLASSES =
  'size-2.5! border-2! border-(--node-accent)! bg-surface!';

/**
 * The canvas node placeholder shell.
 *
 * Provides: an icon slot (category default, spinner while running), 0..N input/output
 * port handles for edge anchoring, a backend-HTML details slot, and status/progress
 * details (status line, progress metric, error badge).
 */
export function WorkflowNodeCard({
  data,
  selected,
}: NodeProps<WorkflowFlowNode>) {
  const { node, status, task, hideDescription } = data;
  const isTrigger = node.category === 'TRIGGER';
  const isTerminal = node.category === 'TERMINAL';
  const icon = STATUS_ICONS[status] ?? pluginIcon(node.type, node.category);
  // The default node id is "<type>-N"; only show the plugin name when the id doesn't already say it.
  const norm = (v: string) => v.toLowerCase().replace(/[^a-z0-9]/g, '');
  const showType = !norm(node.nodeId).startsWith(norm(node.type));
  const detail = progressDetail(status, task);
  const percent = progressPercent(status, task);
  const accent = categoryAccent(node.category);
  const outputPorts = isTerminal
    ? []
    : node.outputPorts.length > 0
      ? node.outputPorts
      : [null];

  return (
    <div
      role="group"
      tabIndex={0}
      aria-label={`${node.nodeId}: ${toNodeStatusLabel(status)}`}
      data-testid={`workflow-node-${node.nodeId}`}
      data-status={status}
      data-selected={selected ? 'true' : undefined}
      style={{ '--node-accent': accent } as React.CSSProperties}
      className={cn(
        'yukta-node bg-surface relative flex size-full flex-col rounded-xl border border-t-[3px] border-t-(--node-accent) outline-none focus-visible:ring-2 focus-visible:ring-(--node-accent)',
        STATUS_CARD_CLASSES[status]
      )}
    >
      {!isTrigger && (
        <Handle
          type="target"
          position={Position.Left}
          className={HANDLE_CLASSES}
          aria-label={`${node.nodeId} input`}
        />
      )}
      {outputPorts.map((port, index) => (
        <Handle
          key={port ?? 'default-out'}
          id={port ?? undefined}
          type="source"
          position={Position.Right}
          style={{ top: outputHandleTop(index, outputPorts.length) }}
          title={port ?? 'output'}
          className={HANDLE_CLASSES}
          aria-label={`${node.nodeId} output ${port ?? 'default'}`}
        />
      ))}
      {outputPorts.length > 1 &&
        outputPorts.map((port, index) => (
          <span
            key={`label-${port}`}
            data-testid="port-label"
            aria-hidden="true"
            style={{ top: outputHandleTop(index, outputPorts.length) }}
            className="text-on-surface-variant bg-surface pointer-events-none absolute right-3 -translate-y-1/2 pl-1 text-xs"
          >
            {port}
          </span>
        ))}

      <div className="flex items-center gap-2.5 px-3 pt-2.5">
        <span
          className="flex size-8 shrink-0 items-center justify-center rounded-lg text-(--node-accent)"
          style={{ background: categoryTint(node.category) }}
        >
          <span
            className={cn(
              'material-symbols-outlined text-lg',
              status === 'running' && 'animate-spin',
              status === 'failed' && 'text-error'
            )}
            data-testid="node-icon"
          >
            {icon}
          </span>
        </span>
        <div className="min-w-0">
          <p className="text-on-surface text-label-md truncate">
            {node.nodeId}
          </p>
          {showType && (
            <p className="text-on-surface-variant text-body-sm truncate">
              {pluginLabel(node.type)}
            </p>
          )}
        </div>
      </div>

      {node.description && !hideDescription && (
        <p className="text-on-surface-variant text-body-sm line-clamp-2 px-3 pt-1.5">
          {node.description}
        </p>
      )}

      <div className="mt-auto flex items-center justify-between px-3 pt-1 pb-2">
        <span
          className={cn(
            'text-on-surface-variant text-body-sm inline-flex items-center gap-1.5',
            status === 'failed' && 'text-error'
          )}
        >
          <span
            aria-hidden="true"
            className={cn('size-2 rounded-full', STATUS_DOT_CLASSES[status])}
          />
          {toNodeStatusLabel(status)}
        </span>
        {detail && (
          <span className="text-on-surface-variant text-body-sm">{detail}</span>
        )}
      </div>
      {percent !== null && (
        <div
          className="bg-surface-container-high h-0.5 w-full"
          aria-hidden="true"
        >
          <div
            className="h-full bg-(--node-accent)"
            style={{ width: `${percent}%` }}
          />
        </div>
      )}
    </div>
  );
}
