// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Handle, Position } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import { NodeDetailsHtml } from './NodeDetailsHtml';
import { toNodeStatusLabel } from '../../lib/statusMapper';
import { cn } from '@/lib/utils';
import { categoryAccent, categoryTint } from '../../lib/categoryAccent';
import type { WorkflowFlowNode } from '../../lib/layout';
import type {
  NodeUiStatus,
  PluginCategory,
  TaskProgress,
} from '../../types/workflow';

const CATEGORY_ICONS: Record<PluginCategory, string> = {
  TRIGGER: 'input',
  PROCESSOR: 'filter_alt',
  TERMINAL: 'save',
};

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
  running: 'bg-(--accent) animate-pulse',
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
  if (status === 'completed') {
    return '100%';
  }
  if (status !== 'running') {
    return null;
  }
  const metadata = task?.metadata ?? {};
  const raw = metadata['progress'] ?? metadata['percent'];
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return `${Math.round(raw)}%`;
  }
  return 'Processing...';
}

function outputHandleTop(index: number, count: number): string {
  return `${((index + 1) / (count + 1)) * 100}%`;
}

const HANDLE_CLASSES = 'size-2.5! border-2! border-(--accent)! bg-surface!';

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
  const icon =
    STATUS_ICONS[status] ??
    (node.category ? CATEGORY_ICONS[node.category] : 'extension');
  const detail = progressDetail(status, task);
  const percent = progressPercent(status, task);
  const accent = categoryAccent(node.category);
  const outputPorts =
    node.outputPorts.length > 0 ? node.outputPorts : isTerminal ? [] : [null];

  return (
    <div
      role="group"
      tabIndex={0}
      aria-label={`${node.nodeId}: ${toNodeStatusLabel(status)}`}
      data-testid={`workflow-node-${node.nodeId}`}
      data-status={status}
      data-selected={selected ? 'true' : undefined}
      style={{ '--accent': accent } as React.CSSProperties}
      className={cn(
        'yukta-node bg-surface relative flex size-full flex-col border border-t-[3px] border-t-(--accent) outline-none focus-visible:ring-2 focus-visible:ring-(--accent)',
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
          className="flex size-8 shrink-0 items-center justify-center text-(--accent)"
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
          {node.type !== node.nodeId && (
            <p className="text-on-surface-variant text-body-sm truncate">
              {node.type}
            </p>
          )}
        </div>
      </div>

      {node.description && !node.uiDesign && !hideDescription && (
        <p className="text-on-surface-variant text-body-sm line-clamp-2 px-3 pt-1.5">
          {node.description}
        </p>
      )}

      {node.uiDesign && (
        <div className="px-3 pt-1">
          <NodeDetailsHtml uiDesign={node.uiDesign} nodeId={node.nodeId} />
        </div>
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
          Status: {toNodeStatusLabel(status)}
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
            className="h-full bg-(--accent)"
            style={{ width: `${percent}%` }}
          />
        </div>
      )}
    </div>
  );
}
