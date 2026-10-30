// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Handle, Position, type NodeProps } from '@xyflow/react';
import { cn } from '@/lib/utils';
import { toNodeStatusLabel } from '../../lib/statusMapper';
import type { StageFlowNode, StageFrameNode } from '../../lib/layout';
import type { NodeUiStatus } from '../../types/workflow';

const DOT: Record<NodeUiStatus, string> = {
  completed: 'bg-success',
  running: 'live-dot bg-primary',
  pending: 'border border-outline',
  failed: 'bg-error',
  paused: 'bg-warning',
  skipped: 'border border-outline',
};

const HANDLE = 'size-2.5! border-2! border-outline! bg-surface!';

function StageStatus({ status }: { status: NodeUiStatus }) {
  return (
    <span
      className={cn(
        'text-body-sm text-on-surface-variant inline-flex items-center gap-1.5',
        status === 'failed' && 'text-error'
      )}
    >
      <span
        aria-hidden="true"
        className={cn('size-2 rounded-full', DOT[status])}
      />
      {toNodeStatusLabel(status)}
    </span>
  );
}

/** A closed stage: its name, node count and aggregate status, drawn in place of its nodes. */
export function StageNodeCard({ data }: NodeProps<StageFlowNode>) {
  const { stage, count, status, onToggle } = data;
  return (
    <div
      role="group"
      aria-label={`Stage ${stage}: ${toNodeStatusLabel(status)}, ${count} nodes`}
      data-testid={`stage-${stage}`}
      data-status={status}
      className={cn(
        'bg-surface-container relative flex size-full flex-col rounded-xl border border-dashed px-3 py-2.5',
        status === 'failed' ? 'border-error' : 'border-outline'
      )}
    >
      <Handle type="target" position={Position.Left} className={HANDLE} />
      <Handle type="source" position={Position.Right} className={HANDLE} />
      <div className="flex items-center gap-2">
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-on-surface-variant text-lg"
        >
          stacks
        </span>
        <p className="text-label-md text-on-surface min-w-0 flex-1 truncate">
          {stage}
        </p>
        <span className="text-body-sm text-muted-foreground tabular-nums">
          {count} nodes
        </span>
      </div>
      <div className="mt-auto flex items-center justify-between">
        <StageStatus status={status} />
        <button
          type="button"
          className="nodrag text-label-sm text-primary hover:underline"
          aria-label={`Expand stage ${stage}`}
          onClick={() => onToggle?.(stage)}
        >
          Expand
        </button>
      </div>
    </div>
  );
}

/** The frame behind an open stage's nodes, with its name, status and a way to close it. */
export function StageFrame({ data }: NodeProps<StageFrameNode>) {
  const { stage, count, status, onToggle } = data;
  return (
    <div
      data-testid={`stage-frame-${stage}`}
      data-status={status}
      className={cn(
        'bg-muted/40 size-full rounded-2xl border border-dashed',
        status === 'failed' ? 'border-error' : 'border-outline-variant'
      )}
    >
      <div className="flex items-center gap-2 px-3 py-2">
        <p className="text-label-md text-on-surface truncate">{stage}</p>
        <span className="text-body-sm text-muted-foreground tabular-nums">
          {count} nodes
        </span>
        <StageStatus status={status} />
        <button
          type="button"
          // xyflow turns pointer events off on a frame that cannot be selected
          style={{ pointerEvents: 'auto' }}
          className="nodrag text-label-sm text-primary ml-auto hover:underline"
          aria-label={`Collapse stage ${stage}`}
          onClick={() => onToggle?.(stage)}
        >
          Collapse
        </button>
      </div>
    </div>
  );
}

/** The canvas switch between stages and every node. */
export function GroupingSwitch({
  grouped,
  onChange,
}: {
  grouped: boolean;
  onChange: (grouped: boolean) => void;
}) {
  return (
    <div
      role="radiogroup"
      aria-label="Canvas grouping"
      className="bg-surface border-border shadow-card absolute top-3 left-3 z-10 flex gap-0.5 rounded-lg border p-0.5"
    >
      {(
        [
          [true, 'Group by stage'],
          [false, 'All nodes'],
        ] as const
      ).map(([value, label]) => (
        <button
          key={label}
          type="button"
          role="radio"
          aria-checked={grouped === value}
          onClick={() => onChange(value)}
          className={cn(
            'text-label-sm rounded-md px-2.5 py-1',
            grouped === value
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
