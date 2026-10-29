// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { buttonVariants } from '@/components/ui/button';
import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router';
import { InlineError } from '@/components/common/InlineError';
import HeaderPortal from '@/components/layout/HeaderPortal';
import {
  controlErrorMessage,
  useExecutionControl,
} from '../hooks/useExecutionControl';
import type { NodeCommand } from './NodeControlsMenu';
import { RunControls } from './RunControls';
import { useBreadcrumbOverride } from '@/lib/breadcrumbOverrides';
import { LogsPanel } from './LogsPanel';
import { PendingApprovals } from './PendingApprovals';
import { ProgressPanel } from './ProgressPanel';
import { RunHistoryDropdown } from './RunHistoryDropdown';
import { WorkflowCanvas } from './WorkflowCanvas';
import { WorkflowTitleBar } from './WorkflowTitleBar';
import { WorkflowVersionsPanel } from './WorkflowVersionsPanel';
import { isTerminalExecStatus, toDisplayExecStatus } from '../lib/statusMapper';
import { useExecutionLogs } from '../hooks/useExecutionLogs';
import { useExecutionProgress } from '../hooks/useExecutionProgress';
import { useExecutionSelection } from '../hooks/useExecutionSelection';
import { useWorkflowExecutions } from '../hooks/useWorkflowExecutions';
import { useWorkflowGraph } from '../hooks/useWorkflowGraph';
import type { TaskProgress } from '../types/workflow';
import { NodeStopDialog } from './NodeStopDialog';
import { StateMessage } from '@/components/common/StateMessage';
import { BreakpointDialog } from './BreakpointDialog';

/** The workflow page: the run, its tasks and logs, and the controls. */

export function WorkflowDetailsPage() {
  const { sessionId = '', workflowId = '' } = useParams<{
    sessionId: string;
    workflowId: string;
  }>();
  const [panelOpen, setPanelOpen] = useState(true);
  const [versionsOpen, setVersionsOpen] = useState(false);
  const [stopping, setStopping] = useState<string | null>(null);

  const graphQuery = useWorkflowGraph(sessionId, workflowId);
  const executionsQuery = useWorkflowExecutions(sessionId, workflowId);
  const executions = useMemo(
    () => executionsQuery.data ?? [],
    [executionsQuery.data]
  );
  const { selectedExecutionId, selectExecution } =
    useExecutionSelection(executions);
  const { progress, isStreaming } = useExecutionProgress(
    sessionId,
    selectedExecutionId
  );
  const logs = useExecutionLogs(
    sessionId,
    selectedExecutionId,
    !isTerminalExecStatus(progress?.status)
  );

  const graph = graphQuery.data;
  const displayName = graph?.description || workflowId;
  useBreadcrumbOverride(workflowId, displayName);

  const nodeControl = useExecutionControl(sessionId, workflowId);
  const [breakpointNode, setBreakpointNode] = useState<string | null>(null);
  // Only handed to the progress panel while an execution is selected.
  const onNodeCommand = (nodeId: string, command: NodeCommand) => {
    const executionId = selectedExecutionId as string;
    if (command === 'restart-from') {
      nodeControl.restartFromNode.mutate(
        { executionId, nodeId },
        { onSuccess: (result) => selectExecution(result.executionId) }
      );
    } else if (command === 'breakpoint') {
      setBreakpointNode(nodeId);
    } else if (command === 'breakpoint/clear') {
      nodeControl.clearBreakpoint.mutate({ executionId, nodeId });
    } else if (command === 'stop') {
      setStopping(nodeId);
    } else if (command === 'unskip') {
      nodeControl.unskipNode.mutate({ executionId, nodeId });
    } else {
      nodeControl.nodeAction.mutate({ executionId, nodeId, action: command });
    }
  };
  const nodeError =
    nodeControl.nodeAction.error ??
    nodeControl.restartFromNode.error ??
    nodeControl.clearBreakpoint.error ??
    nodeControl.stopNode.error ??
    nodeControl.unskipNode.error;

  const tasksByNode = useMemo(() => {
    const byNode: Record<string, TaskProgress | undefined> = {};
    progress?.tasks.forEach((task) => {
      byNode[task.nodeId] = task;
    });
    return byNode;
  }, [progress]);

  if (graphQuery.isLoading || executionsQuery.isLoading) {
    return (
      <div className="px-4 py-6 sm:px-6">
        <StateMessage tone="loading" title="Loading workflow..." />
      </div>
    );
  }

  if (graphQuery.error || executionsQuery.error || !graph) {
    return (
      <div className="px-4 py-6 sm:px-6">
        <StateMessage tone="error" title="Failed to load workflow" />
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
    <div
      className="bg-background flex flex-1 flex-col"
      data-testid="workflow-details-page"
    >
      <HeaderPortal targetId="header-actions">
        <button
          type="button"
          onClick={() => setPanelOpen((value) => !value)}
          aria-pressed={panelOpen}
          className={buttonVariants({ variant: 'outline' })}
        >
          <span className="material-symbols-outlined text-base">
            right_panel_open
          </span>
          Workflow progress
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
            <button
              type="button"
              onClick={() => setVersionsOpen((value) => !value)}
              aria-expanded={versionsOpen}
              className={buttonVariants({ variant: 'outline' })}
            >
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                history
              </span>
              Versions
            </button>
            <Link
              to={`/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflowId)}/edit`}
              className={buttonVariants({ variant: 'outline' })}
            >
              <span className="material-symbols-outlined text-base">edit</span>
              Edit
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

      {versionsOpen && (
        <WorkflowVersionsPanel sessionId={sessionId} workflowId={workflowId} />
      )}

      <PendingApprovals sessionId={sessionId} workflowId={workflowId} />

      {nodeError && (
        <div className="px-6 py-2">
          <InlineError message={controlErrorMessage(nodeError)} />
        </div>
      )}

      {breakpointNode && (
        <BreakpointDialog
          nodeId={breakpointNode}
          pending={nodeControl.setBreakpoint.isPending}
          error={
            nodeControl.setBreakpoint.error
              ? controlErrorMessage(nodeControl.setBreakpoint.error)
              : null
          }
          onClose={() => setBreakpointNode(null)}
          onSubmit={(field, equals) => {
            const executionId = selectedExecutionId as string;
            nodeControl.setBreakpoint.mutate(
              { executionId, nodeId: breakpointNode, field, equals },
              { onSuccess: () => setBreakpointNode(null) }
            );
          }}
        />
      )}

      {stopping && (
        <NodeStopDialog
          nodeId={stopping}
          pending={nodeControl.stopNode.isPending}
          onClose={() => setStopping(null)}
          onSubmit={(options) =>
            nodeControl.stopNode.mutate(
              {
                executionId: selectedExecutionId as string,
                nodeId: stopping,
                ...options,
              },
              { onSettled: () => setStopping(null) }
            )
          }
        />
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

      <LogsPanel
        sessionId={sessionId}
        executionId={selectedExecutionId}
        entries={logs.entries}
        dropped={logs.dropped}
        isStreaming={isStreaming || logs.isStreaming}
      />
    </div>
  );
}
