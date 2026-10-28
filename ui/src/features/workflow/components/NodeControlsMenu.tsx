// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { RequirePermission } from '@/components/common/RequirePermission';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { NodeAction } from '../api/executionControl';
import { useCopyCommand } from '../hooks/useCopyCommand';
import { CopyAsMenu, type CopyChoice } from './CopyAsMenu';
import type { NodeUiStatus } from '../types/workflow';

export type NodeCommand = NodeAction | 'restart-from' | 'breakpoint' | 'breakpoint/clear';

const ITEMS: {
  command: NodeCommand;
  label: string;
  when: (s: NodeUiStatus) => boolean;
}[] = [
  { command: 'pause', label: 'Pause node', when: (s) => s === 'running' },
  { command: 'resume', label: 'Resume node', when: (s) => s === 'paused' },
  {
    command: 'stop',
    label: 'Stop node',
    when: (s) => s === 'running' || s === 'paused',
  },
  {
    command: 'skip',
    label: 'Skip node',
    when: (s) => s === 'pending' || s === 'running',
  },
  { command: 'retry', label: 'Retry node', when: (s) => s === 'failed' },
  {
    command: 'step/enable',
    label: 'Enable step mode',
    when: (s) => s === 'running',
  },
  { command: 'step', label: 'Step once', when: (s) => s === 'paused' },
  { command: 'step/disable', label: 'Disable step mode', when: (s) => s === 'paused' },
  {
    command: 'breakpoint',
    label: 'Set breakpoint…',
    when: (s) => s === 'pending' || s === 'running' || s === 'paused',
  },
  { command: 'restart-from', label: 'Restart from here', when: (s) => s !== 'pending' },
];

const CLEAR_BREAKPOINT = { command: 'breakpoint/clear', label: 'Clear breakpoint' } as const;

/** Per-node actions, listing only those that make sense for the node's current status. */
export function NodeControlsMenu({
  nodeId,
  status,
  onCommand,
  hasBreakpoint = false,
  scope,
}: {
  nodeId: string;
  status: NodeUiStatus;
  onCommand: (nodeId: string, command: NodeCommand) => void;
  /** Whether this tab set a breakpoint on the node, which adds Clear breakpoint. */
  hasBreakpoint?: boolean;
  /** The session and run the node belongs to; with it the menu can copy the equivalent commands. */
  scope?: { sessionId: string; executionId: string };
}) {
  const { status: copied, copy } = useCopyCommand();
  // Every status has at least one applicable action, so the menu is never empty.
  const items = ITEMS.filter((item) => item.when(status)).flatMap((item) =>
    item.command === 'breakpoint' && hasBreakpoint ? [item, CLEAR_BREAKPOINT] : [item]
  );
  const choices: CopyChoice[] = scope
    ? items.flatMap((item): CopyChoice[] => {
        // breakpoints are local to this tab and have no CLI, curl or MCP form
        if (item.command === 'breakpoint' || item.command === 'breakpoint/clear') return [];
        return [
          {
            label: item.label,
            action:
              item.command === 'restart-from'
                ? { kind: 'restartFromNode', ...scope, nodeId }
                : { kind: 'node', verb: item.command, ...scope, nodeId },
          },
        ];
      })
    : [];

  return (
    <>
      {copied && (
        <span role="status" className="text-label-sm text-on-surface-variant">
          {copied === 'copied' ? 'Copied' : 'Copy failed'}
        </span>
      )}
      <DropdownMenu>
        <RequirePermission perm="controlRuns">
          <DropdownMenuTrigger
            aria-label={`Actions for ${nodeId}`}
            className="text-on-surface-variant hover:text-on-surface flex size-6 items-center justify-center"
          >
            <span className="material-symbols-outlined text-base">
              more_vert
            </span>
          </DropdownMenuTrigger>
        </RequirePermission>
        <DropdownMenuContent align="end">
          {items.map((item) => (
            <DropdownMenuItem
              key={item.command}
              onClick={() => onCommand(nodeId, item.command)}
            >
              {item.label}
            </DropdownMenuItem>
          ))}
          {scope && <CopyAsMenu choices={choices} onCopy={copy} />}
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}
