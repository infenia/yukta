// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Handle, Position } from '@xyflow/react';
import { useContext } from 'react';
import { Link } from 'react-router';
import { SubWorkflowLinks } from '../../hooks/useSubWorkflowLinks';
import type { NodeProps } from '@xyflow/react';
import { toNodeStatusLabel, toStatusDisplay } from '../../lib/statusMapper';
import { cn } from '@/lib/utils';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { categoryAccent, categoryTint } from '../../lib/categoryAccent';
import { paletteColor, paletteTint } from '../../lib/paletteColor';
import { pluginIcon, pluginLabel } from '../../lib/pluginIcons';
import {
  nodeSize,
  outputPortsOf,
  portTop,
  type WorkflowFlowNode,
} from '../../lib/layout';
import { portGlyph } from '../../lib/portGlyph';
import type { NodeUiStatus, TaskProgress } from '../../types/workflow';
import { Tip } from '@/components/ui/tooltip';

/** The glyph in the status badge; pending is a hollow dot and running a pulsing one. */
const STATUS_GLYPHS: Partial<Record<NodeUiStatus, string>> = {
  completed: 'check',
  failed: 'close',
  paused: 'pause',
  skipped: 'remove',
};

const STATUS_BADGE_CLASSES: Record<NodeUiStatus, string> = {
  completed: 'bg-success text-white',
  running: 'live-dot bg-(--node-accent)',
  pending: 'border-2 border-outline bg-surface',
  failed: 'bg-error text-white',
  paused: 'bg-warning text-white',
  skipped: 'bg-outline text-white',
};

const STATUS_CARD_CLASSES: Record<NodeUiStatus, string> = {
  completed: 'border-outline-variant',
  running: 'border-transparent',
  pending: 'border-outline-variant opacity-80',
  failed: 'border-error',
  paused: 'border-outline',
  skipped: 'border-outline-variant opacity-50',
};

const TONE_CLASSES = {
  ok: 'border-success!',
  bad: 'border-error!',
  neutral: 'border-(--node-accent)!',
} as const;

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

const HANDLE_CLASSES =
  'size-2.5! border-2! bg-surface! transition-transform hover:scale-150';

/**
 * The canvas node. It speaks in symbols: the plugin icon tile says what it is, a badge on the
 * tile says its state (the tooltip spells it out), a ring around the tile shows progress, an
 * info button holds the description, and output ports sit on the right edge, one row each.
 */
export function WorkflowNodeCard({
  data,
  selected,
}: NodeProps<WorkflowFlowNode>) {
  const { node, status, task, hideDescription, hideStatus } = data;
  const subWorkflowHref = useContext(SubWorkflowLinks).get(node.nodeId);
  const isTrigger = node.category === 'TRIGGER';
  const icon = pluginIcon(node.type, node.category);
  // The default node id is "<type>-N"; only show the id when it says more than the plugin name.
  const norm = (v: string) => v.toLowerCase().replace(/[^a-z0-9]/g, '');
  const showId = !norm(node.nodeId).startsWith(norm(node.type));
  // The raw task status tells e.g. "Timed out" from "Failed"; without one, the bucket's label.
  const statusLabel = task?.status
    ? toStatusDisplay(task.status, 'node').label
    : toNodeStatusLabel(status);
  const percent = progressPercent(status, task);
  const accent = categoryAccent(node.category);
  const color = paletteColor(node.color);
  const outputPorts = outputPortsOf(node);
  const { height } = nodeSize(node);
  const glyph = STATUS_GLYPHS[status];
  const stateTip =
    percent === null ? statusLabel : `${statusLabel} ${Math.round(percent)}%`;

  return (
    <div
      role="group"
      tabIndex={0}
      // Enter opens the node like a click does: the click reaches the canvas through the wrapper
      onKeyDown={(event) => {
        if (event.key === 'Enter' && event.target === event.currentTarget) {
          event.currentTarget.click();
        }
      }}
      aria-label={`${node.nodeId}: ${statusLabel}`}
      data-testid={`workflow-node-${node.nodeId}`}
      data-status={status}
      data-category={node.category ?? undefined}
      data-selected={selected ? 'true' : undefined}
      data-color={color ? node.color : undefined}
      style={
        {
          '--node-accent': accent,
          // a soft wash fading out under the top edge, on top of the card's own background
          backgroundImage: color
            ? `linear-gradient(to bottom, ${paletteTint(node.color)}, transparent 64px)`
            : undefined,
        } as React.CSSProperties
      }
      className={cn(
        'yukta-node group bg-surface relative flex size-full items-center gap-3 border border-t-[3px] border-t-(--node-accent) px-3 outline-none focus-visible:ring-2 focus-visible:ring-(--node-accent)',
        node.category === 'TERMINAL' ? 'rounded-xl rounded-r-md' : 'rounded-xl',
        STATUS_CARD_CLASSES[status]
      )}
    >
      {!isTrigger && (
        <Handle
          type="target"
          position={Position.Left}
          className={cn(HANDLE_CLASSES, 'border-(--node-accent)!')}
          aria-label={`${node.nodeId} input`}
        />
      )}
      {outputPorts.map((port, index) => {
        const { tone } = portGlyph(port);
        const top = portTop(index, outputPorts.length, height);
        return (
          <Handle
            key={port ?? 'default-out'}
            id={port ?? undefined}
            type="source"
            position={Position.Right}
            style={{ top }}
            className={cn(HANDLE_CLASSES, TONE_CLASSES[tone])}
            aria-label={`${node.nodeId} output ${port ?? 'default'}`}
          />
        );
      })}
      {outputPorts.length > 1 &&
        outputPorts.map((port, index) => {
          const { icon: portIcon } = portGlyph(port);
          return (
            <span
              key={`label-${port}`}
              data-testid="port-label"
              aria-hidden="true"
              style={{ top: portTop(index, outputPorts.length, height) }}
              className="border-outline-variant bg-surface text-label-sm text-on-surface-variant shadow-card pointer-events-none absolute left-full ml-2 inline-flex -translate-y-1/2 items-center gap-1 rounded-full border px-1.5 opacity-0 transition-opacity group-hover:opacity-100 group-data-[selected=true]:opacity-100"
            >
              {portIcon && (
                <span className="material-symbols-outlined text-base">
                  {portIcon}
                </span>
              )}
              {port}
            </span>
          );
        })}

      {color && (
        // a marker only: borders and the status badge keep meaning status and category
        <span
          aria-hidden="true"
          data-testid="node-color-stripe"
          className="absolute inset-y-3 left-0 w-1 rounded-r-full"
          style={{ backgroundColor: color }}
        />
      )}

      <Tip label={hideStatus ? null : stateTip}>
        <span
          className="relative shrink-0"
          data-testid="node-tile"
          tabIndex={-1}
        >
          {status === 'running' && (
            <span
              aria-hidden="true"
              data-testid="progress-ring"
              className={cn(
                'absolute -inset-1 rounded-[14px]',
                percent === null
                  ? 'animate-spin border-2 border-transparent border-t-(--node-accent)'
                  : 'node-ring'
              )}
              style={
                percent === null
                  ? undefined
                  : ({
                      '--node-progress': `${percent}%`,
                    } as React.CSSProperties)
              }
            />
          )}
          <span className="bg-surface relative flex size-10 items-center justify-center rounded-xl text-(--node-accent)">
            <span
              className="absolute inset-0 rounded-xl"
              style={{ background: categoryTint(node.category) }}
            />
            <span
              className="material-symbols-outlined relative text-xl"
              data-testid="node-icon"
            >
              {icon}
            </span>
          </span>
          {!hideStatus && (
            <span
              aria-hidden="true"
              data-testid="status-badge"
              className={cn(
                'node-badge border-surface absolute -right-1 -bottom-1 flex size-4 items-center justify-center rounded-full border-2',
                STATUS_BADGE_CLASSES[status]
              )}
            >
              {glyph && (
                <span className="material-symbols-outlined text-[12px]! leading-none">
                  {glyph}
                </span>
              )}
            </span>
          )}
        </span>
      </Tip>

      <div className="min-w-0 flex-1">
        <p className="text-label-md text-on-surface truncate">
          {pluginLabel(node.type)}
        </p>
        {showId && (
          <p className="text-body-sm text-on-surface-variant truncate">
            {node.nodeId}
          </p>
        )}
      </div>

      <div className="flex shrink-0 items-center gap-0.5 pr-1">
        {node.description && !hideDescription && (
          <Popover>
            <Tip label="Description">
              <PopoverTrigger
                aria-label={`Show the description of ${node.nodeId}`}
                className="nodrag text-on-surface-variant hover:text-on-surface inline-flex rounded opacity-60 transition-opacity group-focus-within:opacity-100 group-hover:opacity-100 group-data-[selected=true]:opacity-100 [@media(hover:none)]:opacity-100"
              >
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-lg"
                >
                  info
                </span>
              </PopoverTrigger>
            </Tip>
            <PopoverContent className="nodrag nowheel">
              <p className="text-body-sm text-on-surface whitespace-pre-line">
                {node.description}
              </p>
            </PopoverContent>
          </Popover>
        )}
        {subWorkflowHref && (
          <Tip label={`Open ${node.subWorkflowId}`}>
            <Link
              to={subWorkflowHref}
              aria-label={`Open sub-workflow ${node.subWorkflowId}`}
              // the canvas opens the node's sheet on a node click, which would undo this navigation
              onClick={(event) => event.stopPropagation()}
              className="nodrag text-primary hover:bg-muted inline-flex rounded p-0.5"
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
    </div>
  );
}
