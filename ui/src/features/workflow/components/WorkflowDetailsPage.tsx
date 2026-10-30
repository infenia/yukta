// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { DeleteWorkflowDialog } from '@/features/sessions/components/DeleteDialogs';
import { RequirePermission } from '@/components/common/RequirePermission';
import { Button, buttonVariants } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router';
import { InlineError } from '@/components/common/InlineError';
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
import { GroupingSwitch } from './nodes/StageNodes';
import { WorkflowTitleBar } from './WorkflowTitleBar';
import { WorkflowVersionsPanel } from './WorkflowVersionsPanel';
import { isTerminalExecStatus, toDisplayExecStatus } from '../lib/statusMapper';
import { useExecutionLogs } from '../hooks/useExecutionLogs';
import { useExecutionProgress } from '../hooks/useExecutionProgress';
import { useExecutionSelection } from '../hooks/useExecutionSelection';
import { useWorkflowExecutions } from '../hooks/useWorkflowExecutions';
import { useWorkflowGraph } from '../hooks/useWorkflowGraph';
import { useStageView } from '../hooks/useStageView';
import {
  SubWorkflowLinks,
  useSubWorkflowLinks,
} from '../hooks/useSubWorkflowLinks';
import { useQuery } from '@tanstack/react-query';
import { getWorkflowProgress } from '../api/getWorkflowProgress';
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
  const [deleting, setDeleting] = useState(false);
  const [versionsOpen, setVersionsOpen] = useState(false);
  const [stopping, setStopping] = useState<string | null>(null);
  // Clicking a node on the canvas narrows the logs to it
  const [logNode, setLogNode] = useState<string | null>(null);

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
  // the crumb shows the ID as typed, not title-cased like other path segments
  useBreadcrumbOverride(workflowId, workflowId);

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
  const stages = useStageView(graph, tasksByNode);
  const live = !isTerminalExecStatus(progress?.status);
  const subWorkflowLinks = useSubWorkflowLinks(
    sessionId,
    graph,
    selectedExecutionId,
    live
  );
  // A sub-workflow run links back to the run that started it, which may be another workflow
  const parentId = progress?.parentExecutionId ?? null;
  const parent = useQuery({
    queryKey: ['workflow', sessionId, 'status', parentId],
    queryFn: () => getWorkflowProgress(parentId as string),
    enabled: parentId !== null,
  });

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
      <WorkflowTitleBar
        workflowId={workflowId}
        description={graph.description}
        status={executionStatus}
        actions={
          <>
            {parent.data && (
              <Link
                to={`/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(parent.data.workflowId)}?exec=${encodeURIComponent(parent.data.executionId)}`}
                className={buttonVariants({ variant: 'ghost' })}
              >
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-base"
                >
                  chevron_left
                </span>
                Parent run
              </Link>
            )}
            <RunHistoryDropdown
              executions={executions}
              selectedExecutionId={selectedExecutionId}
              onSelect={selectExecution}
            />
            <RunControls
              sessionId={sessionId}
              workflowId={workflowId}
              executionId={selectedExecutionId}
              status={executionStatus}
              onStarted={selectExecution}
            />
            <Link
              to={`/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflowId)}/edit`}
              className={buttonVariants({ variant: 'outline' })}
            >
              <span className="material-symbols-outlined text-base">edit</span>
              Edit
            </Link>
            <button
              type="button"
              onClick={() => setPanelOpen((value) => !value)}
              aria-pressed={panelOpen}
              aria-label="Workflow progress"
              title="Show or hide the progress panel"
              className={buttonVariants({ variant: 'outline', size: 'icon' })}
            >
              <span className="material-symbols-outlined text-base">
                right_panel_open
              </span>
            </button>
            <DropdownMenu>
              <DropdownMenuTrigger
                render={(state) => (
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label="Workflow actions"
                    {...state}
                  >
                    <span
                      className="material-symbols-outlined"
                      aria-hidden="true"
                    >
                      more_vert
                    </span>
                  </Button>
                )}
              />
              <DropdownMenuContent align="end">
                <DropdownMenuItem
                  onClick={() => setVersionsOpen((value) => !value)}
                >
                  {versionsOpen ? 'Hide versions' : 'Versions'}
                </DropdownMenuItem>
                <RequirePermission perm="deleteSessions">
                  <DropdownMenuItem onClick={() => setDeleting(true)}>
                    Delete workflow…
                  </DropdownMenuItem>
                </RequirePermission>
              </DropdownMenuContent>
            </DropdownMenu>
          </>
        }
      />

      {deleting && (
        <DeleteWorkflowDialog
          sessionId={sessionId}
          workflowId={workflowId}
          onClose={() => setDeleting(false)}
        />
      )}

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

      {/* A fixed-height workspace: the canvas fills it and the panel scrolls inside it, so a
          100-node run no longer stretches the page */}
      <div className="flex h-[clamp(28rem,calc(100dvh-14rem),56rem)]">
        <div className="relative min-w-0 flex-1">
          <SubWorkflowLinks value={subWorkflowLinks}>
            <WorkflowCanvas
              graph={graph}
              tasksByNode={tasksByNode}
              stageView={stages.stageView}
              onNodeClick={setLogNode}
            />
          </SubWorkflowLinks>
          {stages.hasStages && (
            <GroupingSwitch
              grouped={stages.grouped}
              onChange={stages.setGrouped}
            />
          )}
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
        node={logNode}
        onClearNode={() => setLogNode(null)}
      />
    </div>
  );
}
