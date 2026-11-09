// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Handle, Position, type NodeProps } from '@xyflow/react';
import { cn } from '@/lib/utils';
import { Tip } from '@/components/ui/tooltip';
import { toNodeStatusLabel } from '../../lib/statusMapper';
import type { StageFlowNode, StageFrameNode } from '../../lib/layout';
import { paletteColor, paletteTint } from '../../lib/paletteColor';
import type { NodeUiStatus } from '../../types/workflow';

const BADGE: Record<NodeUiStatus, string> = {
  completed: 'bg-success',
  running: 'live-dot bg-primary',
  pending: 'border-2 border-outline bg-surface',
  failed: 'bg-error',
  paused: 'bg-warning',
  skipped: 'bg-outline',
};

const HANDLE = 'size-2.5! border-2! border-outline! bg-surface!';

/** The group's color dot, beside its name; nothing without a color. */
function StageDot({ color }: { color?: string | null }) {
  const solid = paletteColor(color);
  return solid ? (
    <span
      aria-hidden="true"
      data-testid="stage-color-dot"
      className="size-2 shrink-0 rounded-full"
      style={{ backgroundColor: solid }}
    />
  ) : null;
}

/** The state as a dot; the label is in the tooltip and the accessible name. */
function StageStatus({ status }: { status: NodeUiStatus }) {
  return (
    <Tip label={toNodeStatusLabel(status)}>
      <span
        role="img"
        aria-label={toNodeStatusLabel(status)}
        data-testid="stage-status"
        className={cn('size-3 shrink-0 rounded-full', BADGE[status])}
      />
    </Tip>
  );
}

function ToggleButton({
  stage,
  open,
  onToggle,
  className,
}: {
  stage: string;
  open: boolean;
  onToggle?: (stage: string) => void;
  className?: string;
}) {
  const label = `${open ? 'Collapse' : 'Expand'} stage ${stage}`;
  return (
    <Tip label={open ? 'Collapse' : 'Expand'}>
      <button
        type="button"
        // xyflow turns pointer events off on a frame that cannot be selected
        style={{ pointerEvents: 'auto' }}
        className={cn(
          'nodrag text-on-surface-variant hover:bg-muted hover:text-on-surface inline-flex rounded-md p-0.5 transition-colors',
          className
        )}
        aria-label={label}
        onClick={() => onToggle?.(stage)}
      >
        <span
          aria-hidden="true"
          className={cn(
            'material-symbols-outlined text-xl transition-transform duration-300',
            open && 'rotate-180'
          )}
        >
          unfold_more
        </span>
      </button>
    </Tip>
  );
}

/** A closed stage: a stack of cards with the stage name, member count and aggregate state. */
export function StageNodeCard({ data }: NodeProps<StageFlowNode>) {
  const { stage, count, status, onToggle, color } = data;
  return (
    <div
      role="group"
      aria-label={`Stage ${stage}: ${toNodeStatusLabel(status)}, ${count} nodes`}
      data-testid={`stage-${stage}`}
      data-status={status}
      style={{ backgroundColor: paletteTint(color, 16) }}
      className={cn(
        'stage-stack group relative size-full rounded-xl',
        !color && 'bg-surface-container'
      )}
    >
      <Handle type="target" position={Position.Left} className={HANDLE} />
      <Handle type="source" position={Position.Right} className={HANDLE} />
      <div
        className={cn(
          'relative flex size-full items-center gap-3 rounded-xl border border-dashed px-3',
          status === 'failed' ? 'border-error' : 'border-outline'
        )}
      >
        <span className="relative shrink-0">
          <span className="bg-muted text-on-surface-variant flex size-10 items-center justify-center rounded-xl">
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-xl"
            >
              stacks
            </span>
          </span>
          <span
            data-testid="stage-count"
            aria-hidden="true"
            className="bg-foreground text-label-sm text-background absolute -top-1.5 -right-1.5 min-w-5 rounded-full px-1 text-center tabular-nums"
          >
            {count}
          </span>
        </span>
        <StageDot color={color} />
        <p className="text-label-md text-on-surface min-w-0 flex-1 truncate">
          {stage}
        </p>
        <StageStatus status={status} />
        <ToggleButton stage={stage} open={false} onToggle={onToggle} />
      </div>
    </div>
  );
}

/** The frame behind an open stage's nodes, with its name, state and a way to close it. */
export function StageFrame({ data }: NodeProps<StageFrameNode>) {
  const { stage, count, status, onToggle, color } = data;
  return (
    <div
      data-testid={`stage-frame-${stage}`}
      data-status={status}
      style={{ backgroundColor: paletteTint(color, 10) }}
      className={cn(
        'size-full rounded-2xl border border-dashed',
        !color && 'bg-muted/40',
        status === 'failed' ? 'border-error' : 'border-outline-variant'
      )}
    >
      <div className="flex items-center gap-2 px-3 py-2">
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-on-surface-variant text-lg"
        >
          stacks
        </span>
        <StageDot color={color} />
        <p className="text-label-md text-on-surface truncate">{stage}</p>
        <span
          aria-label={`${count} nodes`}
          className="bg-foreground text-label-sm text-background min-w-5 rounded-full px-1 text-center tabular-nums"
        >
          {count}
        </span>
        <StageStatus status={status} />
        <ToggleButton
          stage={stage}
          open
          onToggle={onToggle}
          className="ml-auto"
        />
      </div>
    </div>
  );
}

/** The canvas switch between stages and every node. */
export function GroupingSwitch({
  grouped,
  onChange,
  className = 'absolute top-3 left-3 z-10',
}: {
  grouped: boolean;
  onChange: (grouped: boolean) => void;
  /** Where it sits; by default over the canvas's top-left corner. */
  className?: string;
}) {
  return (
    <div
      role="radiogroup"
      aria-label="Canvas grouping"
      className={cn(
        'border-border bg-surface shadow-card flex gap-0.5 rounded-lg border p-0.5',
        className
      )}
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
