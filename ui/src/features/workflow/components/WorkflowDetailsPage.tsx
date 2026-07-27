// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMemo, useState } from 'react';
import { useParams } from 'react-router';
import HeaderPortal from '@/components/layout/HeaderPortal';
import { useBreadcrumbOverride } from '@/lib/breadcrumbOverrides';
import { LogsPanel } from './LogsPanel';
import { ProgressPanel } from './ProgressPanel';
import { RunHistoryDropdown } from './RunHistoryDropdown';
import { WorkflowCanvas } from './WorkflowCanvas';
import { WorkflowTitleBar } from './WorkflowTitleBar';
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
  const logs = useExecutionLogs(sessionId, selectedExecutionId);

  const graph = graphQuery.data;
  const displayName = graph?.description || workflowId;
  useBreadcrumbOverride(workflowId, displayName);

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
  const executionStatus = progress?.status ?? selectedExecution?.status ?? null;

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
      />

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
          />
        )}
      </div>

      <LogsPanel entries={logs.entries} isStreaming={isStreaming || logs.isStreaming} />
    </div>
  );
}
