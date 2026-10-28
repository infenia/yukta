// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { NodeAction } from '../api/executionControl';
import type { NodeUiStatus } from '../types/workflow';

export type NodeCommand = NodeAction | 'restart-from' | 'unskip';

const ITEMS: {
  command: NodeCommand;
  label: string;
  when: (s: NodeUiStatus) => boolean;
}[] = [
  { command: 'pause', label: 'Pause node', when: (s) => s === 'running' },
  { command: 'resume', label: 'Resume node', when: (s) => s === 'paused' },
  {
    command: 'stop',
    label: 'Stop node…',
    when: (s) => s === 'running' || s === 'paused',
  },
  {
    command: 'skip',
    label: 'Skip node',
    when: (s) => s === 'pending' || s === 'running',
  },
  { command: 'unskip', label: 'Unskip node', when: (s) => s === 'skipped' },
  { command: 'retry', label: 'Retry node', when: (s) => s === 'failed' },
  {
    command: 'step/enable',
    label: 'Enable step mode',
    when: (s) => s === 'running',
  },
  { command: 'step', label: 'Step once', when: (s) => s === 'paused' },
  {
    command: 'step/disable',
    label: 'Disable step mode',
    when: (s) => s === 'paused',
  },
  {
    command: 'restart-from',
    label: 'Restart from here',
    when: (s) => s !== 'pending',
  },
];

/** Per-node actions, listing only those that make sense for the node's current status. */
export function NodeControlsMenu({
  nodeId,
  status,
  onCommand,
}: {
  nodeId: string;
  status: NodeUiStatus;
  onCommand: (nodeId: string, command: NodeCommand) => void;
}) {
  // Every status has at least one applicable action, so the menu is never empty.
  const items = ITEMS.filter((item) => item.when(status));

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        aria-label={`Actions for ${nodeId}`}
        className="text-on-surface-variant hover:text-on-surface flex size-6 items-center justify-center"
      >
        <span className="material-symbols-outlined text-base">more_vert</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {items.map((item) => (
          <DropdownMenuItem
            key={item.command}
            onClick={() => onCommand(nodeId, item.command)}
          >
            {item.label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
