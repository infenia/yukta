// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { ExecutionSummaryBlock } from './ExecutionSummaryBlock';
import { TaskSequenceList } from './TaskSequenceList';
import type { TaskProgress, WorkflowGraph, WorkflowProgress } from '../types/workflow';

/** The closable WORKFLOW PROGRESS side panel: execution summary + task sequence. */
export function ProgressPanel({
  graph,
  progress,
  tasksByNode,
  sessionId,
  onClose,
}: {
  graph: WorkflowGraph;
  progress: WorkflowProgress | null;
  tasksByNode: Record<string, TaskProgress | undefined>;
  sessionId: string;
  onClose: () => void;
}) {
  return (
    <aside
      className="flex w-95 shrink-0 flex-col overflow-y-auto border-l border-outline-variant bg-surface"
      data-testid="progress-panel"
      aria-label="Workflow progress"
    >
      <div className="flex items-center justify-between px-4 pt-4 pb-2">
        <h2 className="text-sm font-semibold tracking-wider text-on-surface">WORKFLOW PROGRESS</h2>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close workflow progress panel"
          className="flex size-6 items-center justify-center text-on-surface-variant transition-colors hover:text-on-surface"
        >
          <span className="material-symbols-outlined text-lg">close</span>
        </button>
      </div>
      <div className="flex flex-col gap-6 p-4">
        <ExecutionSummaryBlock progress={progress} sessionId={sessionId} />
        <TaskSequenceList graph={graph} tasksByNode={tasksByNode} />
      </div>
    </aside>
  );
}
