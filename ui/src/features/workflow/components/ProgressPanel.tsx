// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

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
  return (
    <aside
      className="border-border bg-background flex w-95 shrink-0 flex-col overflow-y-auto border-l"
      data-testid="progress-panel"
      aria-label="Workflow progress"
    >
      <div className="flex items-center justify-between px-4 pt-4 pb-2">
        <h2 className="text-on-surface text-sm font-semibold tracking-wider">
          WORKFLOW PROGRESS
        </h2>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close workflow progress panel"
          className="text-on-surface-variant hover:bg-muted hover:text-on-surface transition-standard flex size-7 items-center justify-center rounded-lg"
        >
          <span className="material-symbols-outlined text-lg">close</span>
        </button>
      </div>
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
        />
      </div>
    </aside>
  );
}
