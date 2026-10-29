// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { isTerminalExecStatus } from '../lib/statusMapper';
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

/** The closable WORKFLOW PROGRESS side panel: execution summary, result + task sequence. */
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
  const [tab, setTab] = useState<'progress' | 'results'>('progress');
  const finished =
    progress && isTerminalExecStatus(progress.status) ? progress : null;
  return (
    <aside
      className="flex w-95 shrink-0 flex-col overflow-y-auto border-l border-border bg-background"
      data-testid="progress-panel"
      aria-label="Workflow progress"
    >
      <div className="flex items-center justify-between px-4 pt-4 pb-2">
        <h2 className="text-label-md text-on-surface">Workflow progress</h2>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close workflow progress panel"
          className="flex size-7 items-center justify-center rounded-lg text-on-surface-variant transition-standard hover:bg-muted hover:text-on-surface"
        >
          <span className="material-symbols-outlined text-lg">close</span>
        </button>
      </div>
      {finished && (
        <div role="tablist" className="flex gap-1 px-4">
          {(['progress', 'results'] as const).map((name) => (
            <button
              key={name}
              type="button"
              role="tab"
              aria-selected={tab === name}
              onClick={() => setTab(name)}
              className={`rounded-lg px-3 py-1 text-label-md capitalize ${
                tab === name
                  ? 'bg-muted text-on-surface'
                  : 'text-on-surface-variant hover:bg-muted'
              }`}
            >
              {name}
            </button>
          ))}
        </div>
      )}
      {finished && tab === 'results' ? (
        <div className="p-4">
          <ResultsExplorer
            graph={graph}
            sessionId={sessionId}
            executionId={finished.executionId}
          />
        </div>
      ) : (
        <div className="flex flex-col gap-6 p-4">
          <ExecutionSummaryBlock progress={progress} sessionId={sessionId} />
          <ExecutionResultBlock
            sessionId={sessionId}
            executionId={progress?.executionId ?? null}
          />
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
        </div>
      )}
    </aside>
  );
}
