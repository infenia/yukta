// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CodeBlock } from '@/components/common/CodeBlock';
import { RequirePermission } from '@/components/common/RequirePermission';
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTitle,
} from '@/components/ui/sheet';
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
import { nodeMenuItems, type NodeCommand } from './NodeControlsMenu';
import { StatusPill } from './StatusPill';

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
export function NodeSheet({
  node,
  task,
  sessionId,
  workflowId,
  executionId,
  entries,
  onCommand,
  onClose,
}: {
  node: WorkflowGraphNode;
  task?: TaskProgress;
  sessionId: string;
  workflowId: string;
  executionId: string | null;
  entries: LogEntry[];
  onCommand: (nodeId: string, command: NodeCommand) => void;
  onClose: () => void;
}) {
  const status = toNodeUiStatus(task?.status);
  const actions = executionId
    ? nodeMenuItems(status, Boolean(task?.breakpoint))
    : [];
  const lines = entries
    .filter((entry) => entry.pluginId === node.nodeId)
    .slice(-MAX_LINES);
  return (
    <Sheet
      open
      modal={false}
      // a click elsewhere on the page (another node, the controls) must not close it
      disablePointerDismissal
      onOpenChange={(open) => !open && onClose()}
    >
      <SheetContent
        backdrop={false}
        // back to the node on the canvas, not the page
        finalFocus={() =>
          document.querySelector<HTMLElement>(
            `[data-testid="workflow-node-${CSS.escape(node.nodeId)}"]`
          )
        }
      >
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <SheetTitle className="break-all">{node.nodeId}</SheetTitle>
            <p
              className="text-body-sm text-on-surface-variant"
              style={{ color: categoryAccent(node.category) }}
            >
              {node.type}
            </p>
          </div>
          <SheetClose
            aria-label="Close node details"
            className="flex size-8 shrink-0 items-center justify-center rounded-lg text-on-surface-variant hover:text-on-surface"
          >
            <span aria-hidden="true" className="material-symbols-outlined">
              close
            </span>
          </SheetClose>
        </div>

        <Section title="Status">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <StatusPill status={task?.status} scope="node" />
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
                <RequirePermission key={item.command} perm="controlRuns">
                  <button
                    type="button"
                    onClick={() => onCommand(node.nodeId, item.command)}
                    className="rounded-lg border border-border px-2.5 py-1 text-label-md hover:bg-muted"
                  >
                    {item.label}
                  </button>
                </RequirePermission>
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
            <pre className="text-terminal-foreground max-h-60 overflow-auto rounded-xl bg-terminal p-2 text-code-sm whitespace-pre-wrap">
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
      </SheetContent>
    </Sheet>
  );
}
