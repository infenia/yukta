// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router';
import { InlineError } from '@/components/common/InlineError';
import HeaderPortal from '@/components/layout/HeaderPortal';
import { controlErrorMessage, useExecutionControl } from '../hooks/useExecutionControl';
import type { NodeCommand } from './NodeControlsMenu';
import { RunControls } from './RunControls';
import { useBreadcrumbOverride } from '@/lib/breadcrumbOverrides';
import { LogsPanel } from './LogsPanel';
import { ProgressPanel } from './ProgressPanel';
import { RunHistoryDropdown } from './RunHistoryDropdown';
import { WorkflowCanvas } from './WorkflowCanvas';
import { WorkflowTitleBar } from './WorkflowTitleBar';
import { isTerminalExecStatus, toDisplayExecStatus } from '../lib/statusMapper';
import { useExecutionLogs } from '../hooks/useExecutionLogs';
import { useExecutionProgress } from '../hooks/useExecutionProgress';
import { useExecutionSelection } from '../hooks/useExecutionSelection';
import { useWorkflowExecutions } from '../hooks/useWorkflowExecutions';
import { useWorkflowGraph } from '../hooks/useWorkflowGraph';
import type { TaskProgress } from '../types/workflow';

export function WorkflowDetailsPage() {
  const { sessionId = '', workflowId = '' } = useParams<{
    sessionId: string;
    workflowId: string;
  }>();
  const [panelOpen, setPanelOpen] = useState(true);

  const graphQuery = useWorkflowGraph(sessionId, workflowId);
  const executionsQuery = useWorkflowExecutions(sessionId, workflowId);
  const executions = useMemo(() => executionsQuery.data ?? [], [executionsQuery.data]);
  const { selectedExecutionId, selectExecution } = useExecutionSelection(executions);
  const { progress, isStreaming } = useExecutionProgress(sessionId, selectedExecutionId);
  const logs = useExecutionLogs(
    sessionId,
    selectedExecutionId,
    !isTerminalExecStatus(progress?.status)
  );

  const graph = graphQuery.data;
  const displayName = graph?.description || workflowId;
  useBreadcrumbOverride(workflowId, displayName);

  const nodeControl = useExecutionControl(sessionId, workflowId);
  // Only handed to the progress panel while an execution is selected.
  const onNodeCommand = (nodeId: string, command: NodeCommand) => {
    const executionId = selectedExecutionId as string;
    if (command === 'restart-from') {
      nodeControl.restartFromNode.mutate(
        { executionId, nodeId },
        { onSuccess: (result) => selectExecution(result.executionId) }
      );
    } else {
      nodeControl.nodeAction.mutate({ executionId, nodeId, action: command });
    }
  };
  const nodeError = nodeControl.nodeAction.error ?? nodeControl.restartFromNode.error;

  const tasksByNode = useMemo(() => {
    const byNode: Record<string, TaskProgress | undefined> = {};
    progress?.tasks.forEach((task) => {
      byNode[task.nodeId] = task;
    });
    return byNode;
  }, [progress]);

  if (graphQuery.isLoading || executionsQuery.isLoading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-on-surface-variant">Loading workflow...</p>
      </div>
    );
  }

  if (graphQuery.error || executionsQuery.error || !graph) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-error">Failed to load workflow</p>
      </div>
    );
  }

  const selectedExecution = executions.find(
    (execution) => execution.executionId === selectedExecutionId
  );
  const executionStatus = toDisplayExecStatus(
    progress?.status ?? selectedExecution?.status,
    progress?.controlState
  );

  return (
    <div className="flex flex-1 flex-col bg-background" data-testid="workflow-details-page">
      <HeaderPortal targetId="header-actions">
        <button
          type="button"
          onClick={() => setPanelOpen((value) => !value)}
          aria-pressed={panelOpen}
          className="flex h-8 items-center gap-2 border border-outline-variant px-3 text-xs font-semibold tracking-wider text-on-surface transition-colors hover:bg-surface-container"
        >
          <span className="material-symbols-outlined text-base">right_panel_open</span>
          WORKFLOW PROGRESS
        </button>
      </HeaderPortal>
      <HeaderPortal targetId="breadcrumb-actions">
        <RunHistoryDropdown
          executions={executions}
          selectedExecutionId={selectedExecutionId}
          onSelect={selectExecution}
        />
      </HeaderPortal>

      <WorkflowTitleBar
        displayName={displayName}
        workflowId={workflowId}
        status={executionStatus}
        actions={
          <>
            <Link
              to={`/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflowId)}/edit`}
              className="flex h-8 items-center gap-1 border border-outline-variant px-3 text-xs font-semibold text-on-surface no-underline hover:bg-surface-container"
            >
              <span className="material-symbols-outlined text-base">edit</span>Edit
            </Link>
            <RunControls
              sessionId={sessionId}
              workflowId={workflowId}
              executionId={selectedExecutionId}
              status={executionStatus}
              onStarted={selectExecution}
            />
          </>
        }
      />

      {nodeError && (
        <div className="px-6 py-2">
          <InlineError message={controlErrorMessage(nodeError)} />
        </div>
      )}

      <div className="flex min-h-100 flex-1">
        <div className="min-w-0 flex-1">
          <WorkflowCanvas graph={graph} tasksByNode={tasksByNode} />
        </div>
        {panelOpen && (
          <ProgressPanel
            graph={graph}
            progress={progress}
            tasksByNode={tasksByNode}
            sessionId={sessionId}
            onClose={() => setPanelOpen(false)}
            onNodeCommand={selectedExecutionId ? onNodeCommand : undefined}
          />
        )}
      </div>

      <LogsPanel entries={logs.entries} isStreaming={isStreaming || logs.isStreaming} />
    </div>
  );
}
