// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useContext, useState } from 'react';
import { Link } from 'react-router';
import { Callout } from '@/components/common/Callout';
import { SubWorkflowLinks } from '../hooks/useSubWorkflowLinks';
import {
  isTerminalExecStatus,
  toNodeUiStatus,
  toStatusDisplay,
} from '../lib/statusMapper';
import { StatusPill } from './StatusPill';
import { ResultsExplorer } from './ResultsExplorer';
import { ExecutionResultBlock } from './ExecutionResultBlock';
import { ExecutionSummaryBlock } from './ExecutionSummaryBlock';
import type { NodeCommand } from './NodeControlsMenu';
import { TaskSequenceList } from './TaskSequenceList';
import type {
  TaskProgress,
  WorkflowGraph,
  WorkflowProgress,
} from '../types/workflow';

/** Longest reason the callout shows; the node's full error is in the logs. */
const REASON_CHARS = 240;

/** Names the first node that failed (in run order), its reason, and the sub-workflow run to open. */
function FailureCallout({
  graph,
  tasksByNode,
}: {
  graph: WorkflowGraph;
  tasksByNode: Record<string, TaskProgress | undefined>;
}) {
  const links = useContext(SubWorkflowLinks);
  const order =
    graph.topologicalOrder.length > 0
      ? graph.topologicalOrder
      : graph.nodes.map((node) => node.nodeId);
  const failed = order.filter(
    (nodeId) => toNodeUiStatus(tasksByNode[nodeId]?.status) === 'failed'
  );
  if (failed.length === 0) return null;
  const [first] = failed;
  const error = tasksByNode[first]?.metadata?.['error'];
  const reason = typeof error === 'string' ? error.trim().split('\n')[0] : null;
  const href = links.get(first);
  return (
    <Callout kind="danger" title={`${first} failed.`}>
      {reason && (
        <p className="text-code-sm wrap-break-word">
          {reason.length > REASON_CHARS
            ? `${reason.slice(0, REASON_CHARS)}…`
            : reason}
        </p>
      )}
      {failed.length > 1 && <p>{failed.length - 1} more failed.</p>}
      {href && (
        <Link to={href} className="text-primary font-semibold hover:underline">
          Open sub-workflow run
        </Link>
      )}
    </Callout>
  );
}

type Tab = 'progress' | 'result' | 'details';

/** The closable WORKFLOW PROGRESS side panel: the task sequence, the result and the run details. */
export function ProgressPanel({
  graph,
  progress,
  tasksByNode,
  sessionId,
  onClose,
  onNodeCommand,
}: {
  graph: WorkflowGraph;
  progress: WorkflowProgress | null;
  tasksByNode: Record<string, TaskProgress | undefined>;
  sessionId: string;
  onClose: () => void;
  onNodeCommand?: (nodeId: string, command: NodeCommand) => void;
}) {
  const [tab, setTab] = useState<Tab>('progress');
  const finished =
    progress && isTerminalExecStatus(progress.status) ? progress : null;
  return (
    <aside
      className="animate-in border-border bg-background fade-in-0 slide-in-from-right-4 flex w-full shrink-0 flex-col overflow-y-auto border-l duration-150"
      data-testid="progress-panel"
      aria-label="Workflow progress"
    >
      <div className="flex items-center justify-between gap-3 px-4 pt-4 pb-3">
        <div className="flex min-w-0 items-center gap-3">
          {/* the run's status as a tile, as the node panel does for a node */}
          <StatusPill
            status={progress?.status}
            iconOnly
            className="size-11 rounded-xl [&_.material-symbols-outlined]:text-[24px]!"
          />
          <div className="min-w-0">
            <h2 className="text-headline-md text-on-surface">
              Workflow progress
            </h2>
            <p className="text-label-md text-on-surface-variant">
              {toStatusDisplay(progress?.status).label}
            </p>
          </div>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close workflow progress panel"
          className="text-on-surface-variant transition-standard hover:bg-muted hover:text-on-surface flex size-8 shrink-0 items-center justify-center rounded-lg"
        >
          <span className="material-symbols-outlined">close</span>
        </button>
      </div>
      <div role="tablist" className="border-border flex gap-x-4 border-b px-4">
        {(
          [
            ['progress', 'Progress'],
            ['result', 'Result'],
            ['details', 'Details'],
          ] as const
        ).map(([name, label]) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={tab === name}
            onClick={() => setTab(name)}
            className={`text-label-md -mb-px h-9 shrink-0 border-b-2 px-0.5 ${
              tab === name
                ? 'border-primary text-on-surface'
                : 'text-on-surface-variant hover:text-on-surface border-transparent'
            }`}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="flex flex-col gap-6 p-4">
        {tab === 'progress' && (
          <>
            <FailureCallout graph={graph} tasksByNode={tasksByNode} />
            <TaskSequenceList
              graph={graph}
              tasksByNode={tasksByNode}
              onNodeCommand={onNodeCommand}
              scope={
                progress
                  ? { sessionId, executionId: progress.executionId }
                  : undefined
              }
            />
          </>
        )}
        {tab === 'result' && (
          <>
            <ExecutionResultBlock
              sessionId={sessionId}
              executionId={progress?.executionId ?? null}
            />
            {finished ? (
              <ResultsExplorer
                sessionId={sessionId}
                executionId={finished.executionId}
              />
            ) : (
              <p className="text-body-sm text-muted-foreground">
                Every node&apos;s output can be explored once the run ends.
              </p>
            )}
          </>
        )}
        {tab === 'details' && (
          <ExecutionSummaryBlock progress={progress} sessionId={sessionId} />
        )}
      </div>
    </aside>
  );
}
