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
import { formatDuration } from '../lib/timeFormat';
import { DateTimeStamp, Stamp } from './DateTimeStamp';
import type { TaskProgress, WorkflowGraphNode } from '../types/workflow';
import { Button } from '@/components/ui/button';
import { ICON_TONE } from '@/components/ui/iconTone';
import { nodeActionSlots, type NodeCommand } from './NodeControlsMenu';
import { StatusPill } from './StatusPill';

/** The icon on each action button. */
const ACTION_ICONS: Record<string, string> = {
  pause: 'pause',
  resume: 'play_arrow',
  stop: 'stop',
  skip: 'skip_next',
  unskip: 'undo',
  retry: 'replay',
  'step/enable': 'footprint',
  step: 'step_over',
  'step/disable': 'do_not_step',
  breakpoint: 'adjust',
  'breakpoint/clear': 'block',
  'restart-from': 'restart_alt',
};

/** The hover tint of each action cell: stop is destructive, the rest neutral or primary. */
const ACTION_TONE: Partial<Record<NodeCommand, keyof typeof ICON_TONE>> = {
  pause: 'attention',
  resume: 'success',
  stop: 'destructive',
  retry: 'primary',
  'restart-from': 'primary',
};

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

/** The server sends the input as JSON text (cut when long); a cut or plain text stays as it is. */
function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
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
 * its configuration (secrets masked by the server), a link to its log lines and, for a terminal, what it
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
  tab,
  onTab,
  closing = false,
  onCommand,
  onShowLogs,
  onClose,
}: {
  node: WorkflowGraphNode;
  task?: TaskProgress;
  sessionId: string;
  workflowId: string;
  executionId: string | null;
  /** Which tab is shown; the Supervisor tab opens from a node's owner icon. */
  tab: SheetTab;
  onTab: (tab: SheetTab) => void;
  /** Plays the exit animation; the page unmounts it a moment later. */
  closing?: boolean;
  onCommand: (nodeId: string, command: NodeCommand) => void;
  /** Shows only this node's lines in the logs dock. */
  onShowLogs?: (nodeId: string) => void;
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
    ? nodeActionSlots(status, Boolean(task?.breakpoint))
    : [];
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
          ? 'animate-out fade-out-0 fill-mode-forwards slide-out-to-right-4'
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
          <div className="flex shrink-0 items-center gap-1">
            {onShowLogs && (
              <button
                type="button"
                onClick={() => onShowLogs(node.nodeId)}
                aria-label="Show this node's logs"
                title="Show this node's logs"
                className="text-on-surface-variant transition-standard hover:bg-muted hover:text-on-surface flex size-8 items-center justify-center rounded-lg"
              >
                <span aria-hidden="true" className="material-symbols-outlined">
                  terminal
                </span>
              </button>
            )}
            <button
              type="button"
              onClick={onClose}
              aria-label="Close node details"
              className="text-on-surface-variant transition-standard hover:bg-muted hover:text-on-surface flex size-8 items-center justify-center rounded-lg"
            >
              <span aria-hidden="true" className="material-symbols-outlined">
                close
              </span>
            </button>
          </div>
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
              </div>
              <dl className="grid grid-cols-[repeat(auto-fit,minmax(6rem,1fr))] gap-x-3 gap-y-2">
                <Stamp label="Start">
                  <DateTimeStamp iso={task?.startTime} />
                </Stamp>
                <Stamp label="End">
                  <DateTimeStamp iso={task?.endTime} />
                </Stamp>
                <Stamp label="Dur">
                  {formatDuration(task?.startTime, task?.endTime)}
                </Stamp>
              </dl>
            </Section>

            {actions.length > 0 && (
              <Section title="Actions">
                {/* Every action keeps its cell; the ones that do not apply now are disabled */}
                <div className="border-border bg-surface-container-low grid w-full grid-cols-6 gap-1 rounded-xl border p-1">
                  {actions.map((item) => (
                    <RequireControl
                      key={item.command}
                      perm="controlRuns"
                      command={NODE_CAPABILITY[item.command] ?? item.command}
                      target={{ execution: executionId, node: node.nodeId }}
                    >
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        aria-label={item.label}
                        title={
                          item.enabled
                            ? item.label
                            : `${item.label.replace('…', '')}: not available while ${status}`
                        }
                        disabled={!item.enabled}
                        className={cn(
                          'transition-standard h-9 w-full rounded-lg disabled:opacity-30',
                          ICON_TONE[ACTION_TONE[item.command] ?? 'neutral']
                        )}
                        onClick={() => onCommand(node.nodeId, item.command)}
                      >
                        <span
                          aria-hidden="true"
                          className="material-symbols-outlined text-lg"
                        >
                          {ACTION_ICONS[item.command]}
                        </span>
                      </Button>
                    </RequireControl>
                  ))}
                </div>
              </Section>
            )}

            {typeof task?.metadata?.input === 'string' && (
              <Section title="Started with">
                <CodeBlock
                  code={prettyJson(task.metadata.input)}
                  label="input"
                />
              </Section>
            )}

            <Section title="Config">
              <Config
                sessionId={sessionId}
                workflowId={workflowId}
                nodeId={node.nodeId}
              />
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
