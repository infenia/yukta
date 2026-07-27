// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Handle, Position } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import { NodeDetailsHtml } from './NodeDetailsHtml';
import { toNodeStatusLabel } from '../../lib/statusMapper';
import { cn } from '@/lib/utils';
import type { WorkflowFlowNode } from '../../lib/layout';
import type { NodeUiStatus, PluginCategory, TaskProgress } from '../../types/workflow';

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
  running: 'border-primary shadow-[0_0_0_1px_var(--color-primary),0_4px_12px_rgba(15,98,254,0.25)]',
  pending: 'border-outline-variant opacity-70',
  failed: 'border-error',
  paused: 'border-outline',
  skipped: 'border-outline-variant opacity-50',
};

function progressDetail(status: NodeUiStatus, task?: TaskProgress): string | null {
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

const HANDLE_CLASSES = 'size-2! border! border-outline! bg-surface!';

/**
 * The canvas node placeholder shell.
 *
 * Provides: an icon slot (category default, spinner while running), 0..N input/output
 * port handles for edge anchoring, a backend-HTML details slot, and status/progress
 * details (status line, progress metric, error badge).
 */
export function WorkflowNodeCard({ data }: NodeProps<WorkflowFlowNode>) {
  const { node, status, task } = data;
  const isTrigger = node.category === 'TRIGGER';
  const isTerminal = node.category === 'TERMINAL';
  const icon =
    STATUS_ICONS[status] ?? (node.category ? CATEGORY_ICONS[node.category] : 'extension');
  const detail = progressDetail(status, task);
  const outputPorts = node.outputPorts.length > 0 ? node.outputPorts : isTerminal ? [] : [null];

  return (
    <div
      role="group"
      tabIndex={0}
      aria-label={`${node.nodeId}: ${toNodeStatusLabel(status)}`}
      data-testid={`workflow-node-${node.nodeId}`}
      className={cn(
        'flex size-full flex-col border bg-surface outline-none focus-visible:ring-2 focus-visible:ring-primary',
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

      <div className="flex items-center gap-2 px-3 pt-2.5">
        <span
          className={cn(
            'material-symbols-outlined text-base',
            status === 'running' && 'animate-spin text-primary',
            status === 'failed' && 'text-error'
          )}
          data-testid="node-icon"
        >
          {icon}
        </span>
        <span className="truncate text-sm font-semibold text-on-surface">{node.nodeId}</span>
      </div>

      {node.uiDesign && (
        <div className="px-3 pt-1">
          <NodeDetailsHtml uiDesign={node.uiDesign} nodeId={node.nodeId} />
        </div>
      )}

      <div className="mt-auto flex items-center justify-between px-3 pt-1 pb-2.5">
        <span
          className={cn('text-xs text-on-surface-variant', status === 'failed' && 'text-error')}
        >
          Status: {toNodeStatusLabel(status)}
        </span>
        {detail && <span className="text-xs text-on-surface-variant">{detail}</span>}
      </div>
    </div>
  );
}
