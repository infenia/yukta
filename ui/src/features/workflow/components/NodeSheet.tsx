// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CodeBlock } from '@/components/common/CodeBlock';
import { cn } from '@/lib/utils';
import { NodeSupervisorTab } from '@/features/supervisors/components/NodeSupervisorTab';
import { RequireControl } from '@/features/supervisors/components/RequireControl';
import { getWorkflowDefinition } from '../api/getWorkflowDefinition';
import { useExecutionResults } from '../hooks/useExecutionResults';
import { categoryAccent } from '../lib/categoryAccent';
import { toNodeUiStatus } from '../lib/statusMapper';
import { formatClockTime, formatDuration } from '../lib/timeFormat';
import type {
  LogEntry,
  TaskProgress,
  WorkflowGraphNode,
} from '../types/workflow';
import { Button } from '@/components/ui/button';
import { nodeMenuItems, type NodeCommand } from './NodeControlsMenu';
import { StatusPill } from './StatusPill';

/** The icon on each action button. */
const ACTION_ICONS: Record<string, string> = {
  pause: 'pause',
  resume: 'play_arrow',
  stop: 'stop',
  skip: 'redo',
  unskip: 'undo',
  retry: 'replay',
  'step/enable': 'footprint',
  step: 'step',
  'step/disable': 'footprint',
  breakpoint: 'adjust',
  'breakpoint/clear': 'cancel',
  'restart-from': 'restart_alt',
};

/** Log lines shown per node; the logs dock holds the full run. */
const MAX_LINES = 200;

const Section = ({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) => (
  <section className="flex flex-col gap-1.5">
    <h3 className="text-overline text-on-surface-variant">{title}</h3>
    {children}
  </section>
);

const Empty = ({ children }: { children: ReactNode }) => (
  <p className="text-body-sm text-on-surface-variant">{children}</p>
);

function Config({
  sessionId,
  workflowId,
  nodeId,
}: {
  sessionId: string;
  workflowId: string;
  nodeId: string;
}) {
  const definition = useQuery({
    queryKey: ['sessions', sessionId, 'workflows', workflowId, 'definition'],
    queryFn: () => getWorkflowDefinition(sessionId, workflowId),
  });
  if (definition.isError) {
    return <Empty>The configuration could not be loaded.</Empty>;
  }
  if (!definition.data) {
    return <Empty>Loading…</Empty>;
  }
  const config = definition.data.nodes.find((n) => n.nodeId === nodeId)?.config;
  return config && Object.keys(config).length > 0 ? (
    <CodeBlock code={JSON.stringify(config, null, 2)} label="configuration" />
  ) : (
    <Empty>No configuration.</Empty>
  );
}

function Results({
  sessionId,
  executionId,
  nodeId,
}: {
  sessionId: string;
  executionId: string;
  nodeId: string;
}) {
  const { results } = useExecutionResults(sessionId, executionId);
  const mine = results.filter((event) => event.nodeId === nodeId);
  return mine.length > 0 ? (
    <div className="flex flex-col gap-2">
      {mine.map((event) => (
        <CodeBlock
          key={event.sequence}
          code={JSON.stringify(event.payload, null, 2)}
          label="result"
        />
      ))}
    </div>
  ) : (
    <Empty>No result yet.</Empty>
  );
}

/**
 * Everything about one node of a run in one place: its status and times, the actions that apply,
 * its configuration (secrets masked by the server), its log lines and, for a terminal, what it
 * received. Opened by `?node=<id>` on the run page.
 */
/** The capability the server names for each node action (others are checked by role only). */
const NODE_CAPABILITY: Record<string, string> = {
  pause: 'pauseNode',
  resume: 'resumeNode',
  stop: 'stopNode',
  skip: 'skipNode',
  step: 'stepNode',
  'step/enable': 'stepNode',
  'step/disable': 'stepNode',
};

export type SheetTab = 'node' | 'supervisor';

const TABS: [SheetTab, string][] = [
  ['node', 'Node'],
  ['supervisor', 'Supervisor'],
];

export function NodeSheet({
  node,
  task,
  sessionId,
  workflowId,
  executionId,
  entries,
  tab,
  onTab,
  closing = false,
  onCommand,
  onClose,
}: {
  node: WorkflowGraphNode;
  task?: TaskProgress;
  sessionId: string;
  workflowId: string;
  executionId: string | null;
  entries: LogEntry[];
  /** Which tab is shown; the Supervisor tab opens from a node's owner icon. */
  tab: SheetTab;
  onTab: (tab: SheetTab) => void;
  /** Plays the exit animation; the page unmounts it a moment later. */
  closing?: boolean;
  onCommand: (nodeId: string, command: NodeCommand) => void;
  onClose: () => void;
}) {
  // Escape closes it from anywhere on the page, as the modal-less sheet did
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);
  const status = toNodeUiStatus(task?.status);
  const actions = executionId
    ? nodeMenuItems(status, Boolean(task?.breakpoint))
    : [];
  const lines = entries
    .filter((entry) => entry.pluginId === node.nodeId)
    .slice(-MAX_LINES);
  return (
    // Over the progress panel, exactly its size: its parent is the panel's box, so hiding the
    // panel hides this too. Not modal: the canvas and the controls stay usable behind it.
    <aside
      role="dialog"
      aria-modal="false"
      aria-labelledby="node-sheet-title"
      data-testid="node-sheet"
      data-closing={closing || undefined}
      className={cn(
        'border-border bg-background absolute inset-0 z-10 flex flex-col gap-3 overflow-y-auto border-l p-4 duration-150',
        closing
          ? 'animate-out fade-out-0 slide-out-to-right-4 fill-mode-forwards'
          : 'animate-in fade-in-0 slide-in-from-right-4'
      )}
    >
      <>
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <h2
              id="node-sheet-title"
              className="text-headline-md text-on-surface break-all"
            >
              {node.nodeId}
            </h2>
            <p
              className="text-body-sm text-on-surface-variant"
              style={{ color: categoryAccent(node.category) }}
            >
              {node.type}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close node details"
            className="text-on-surface-variant hover:text-on-surface flex size-8 shrink-0 items-center justify-center rounded-lg"
          >
            <span aria-hidden="true" className="material-symbols-outlined">
              close
            </span>
          </button>
        </div>

        <div
          role="tablist"
          aria-label="Node details"
          className="border-border flex gap-x-4 border-b"
        >
          {TABS.map(([id, label]) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={tab === id}
              onClick={() => onTab(id)}
              className={cn(
                'text-label-md -mb-px h-9 shrink-0 border-b-2 px-0.5',
                tab === id
                  ? 'border-primary text-on-surface'
                  : 'text-on-surface-variant hover:text-on-surface border-transparent'
              )}
            >
              {label}
            </button>
          ))}
        </div>

        {tab === 'supervisor' ? (
          <NodeSupervisorTab
            sessionId={sessionId}
            workflowId={workflowId}
            executionId={executionId}
            nodeId={node.nodeId}
          />
        ) : (
          <>
            <Section title="Status">
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
                <StatusPill status={task?.status} scope="node" iconOnly />
                <span className="text-body-sm num text-on-surface-variant">
                  {formatClockTime(task?.startTime)} to{' '}
                  {formatClockTime(task?.endTime)}
                  {' · '}
                  {formatDuration(task?.startTime, task?.endTime)}
                </span>
              </div>
            </Section>

            {actions.length > 0 && (
              <Section title="Actions">
                <div className="flex flex-wrap gap-1.5">
                  {actions.map((item) => (
                    <RequireControl
                      key={item.command}
                      perm="controlRuns"
                      command={NODE_CAPABILITY[item.command] ?? item.command}
                      target={{ execution: executionId, node: node.nodeId }}
                    >
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => onCommand(node.nodeId, item.command)}
                      >
                        <span
                          aria-hidden="true"
                          className="material-symbols-outlined text-base"
                        >
                          {ACTION_ICONS[item.command]}
                        </span>
                        {item.label}
                      </Button>
                    </RequireControl>
                  ))}
                </div>
              </Section>
            )}

            <Section title="Config">
              <Config
                sessionId={sessionId}
                workflowId={workflowId}
                nodeId={node.nodeId}
              />
            </Section>

            <Section title="Logs">
              {lines.length > 0 ? (
                <pre className="text-terminal-foreground bg-terminal text-code-sm max-h-60 overflow-auto rounded-xl p-2 whitespace-pre-wrap">
                  {lines.map((l) => l.message).join('\n')}
                </pre>
              ) : (
                <Empty>No log lines from this node.</Empty>
              )}
            </Section>

            {node.category === 'TERMINAL' && executionId && (
              <Section title="Result">
                <Results
                  sessionId={sessionId}
                  executionId={executionId}
                  nodeId={node.nodeId}
                />
              </Section>
            )}
          </>
        )}
      </>
    </aside>
  );
}
