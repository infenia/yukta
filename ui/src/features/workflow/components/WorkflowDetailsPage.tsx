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
import { cn } from '@/lib/utils';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { InlineError } from '@/components/common/InlineError';
import {
  controlErrorMessage,
  useExecutionControl,
} from '../hooks/useExecutionControl';
import type { NodeCommand } from './NodeControlsMenu';
import { RunControls } from './RunControls';
import { LogsPanel } from './LogsPanel';
import { PendingApprovals } from './PendingApprovals';
import { ProgressPanel } from './ProgressPanel';
import { RunHistoryDropdown } from './RunHistoryDropdown';
import { WorkflowCanvas } from './WorkflowCanvas';
import { GroupingSwitch } from './nodes/StageNodes';
import { RunTimeline, ViewSwitch, type RunView } from './RunTimeline';
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
import { NodeSheet } from './NodeSheet';
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
  // Clicking a node on the canvas opens its sheet; the node is in the URL so the view can be shared
  const [searchParams, setSearchParams] = useSearchParams();
  const openNode = searchParams.get('node');
  const setOpenNode = (nodeId: string | null) =>
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (nodeId) {
          next.set('node', nodeId);
        } else {
          next.delete('node');
        }
        return next;
      },
      { replace: true }
    );
  const [view, setView] = useState<RunView>('graph');

  const graphQuery = useWorkflowGraph(sessionId, workflowId);
  const executionsQuery = useWorkflowExecutions(sessionId, workflowId);
  const executions = useMemo(
    () => executionsQuery.data ?? [],
    [executionsQuery.data]
  );
  const navigate = useNavigate();
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
  const openGraphNode = graph?.nodes.find((n) => n.nodeId === openNode);
  const live = !isTerminalExecStatus(progress?.status);
  const [follow, setFollow] = useState(true);
  const [findQuery, setFindQuery] = useState('');
  // only a run that is going has running nodes to follow
  const followable = progress !== null && live;
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
    // Exactly the screen's height (less the phone tab bar): the workspace scrolls inside, so the logs dock stays at the bottom edge
    <div
      className="bg-background flex h-[calc(100dvh-var(--spacing-header)-4rem)] flex-col sm:h-[calc(100dvh-var(--spacing-header))]"
      data-testid="workflow-details-page"
    >
      <WorkflowTitleBar
        workflowId={workflowId}
        description={graph.description}
        status={executionStatus}
        runGroup={
          <>
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
          </>
        }
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
            <Link
              to={`/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflowId)}/edit`}
              className={buttonVariants({ variant: 'outline' })}
            >
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                edit
              </span>
              Edit
            </Link>
            <RequirePermission perm="deleteSessions">
              <button
                type="button"
                onClick={() => setDeleting(true)}
                className={buttonVariants({ variant: 'outline' })}
              >
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-base"
                >
                  delete
                </span>
                Delete
              </button>
            </RequirePermission>
            <button
              type="button"
              onClick={() => setPanelOpen((value) => !value)}
              aria-pressed={panelOpen}
              aria-label="Workflow progress"
              title={panelOpen ? 'Hide progress panel' : 'Show progress panel'}
              className={buttonVariants({ variant: 'outline', size: 'icon' })}
            >
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                {panelOpen ? 'right_panel_close' : 'right_panel_open'}
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
                {selectedExecutionId && (
                  <DropdownMenuItem
                    onClick={() =>
                      void navigate(
                        `/control?execution=${encodeURIComponent(selectedExecutionId)}`
                      )
                    }
                  >
                    Control commands of this run
                  </DropdownMenuItem>
                )}
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
        <WorkflowVersionsPanel
          sessionId={sessionId}
          workflowId={workflowId}
          liveRun={live}
        />
      )}

      <PendingApprovals
        sessionId={sessionId}
        workflowId={workflowId}
        live={live}
      />

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

      {openGraphNode && (
        <NodeSheet
          node={openGraphNode}
          task={tasksByNode[openGraphNode.nodeId]}
          sessionId={sessionId}
          workflowId={workflowId}
          executionId={selectedExecutionId}
          entries={logs.entries}
          onCommand={onNodeCommand}
          onClose={() => setOpenNode(null)}
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

      {/* The workspace fills the rest of the screen: the canvas over the logs dock, the progress
          panel beside both, so a 100-node run no longer stretches the page */}
      {/* The canvas nodes and the failure callout both open sub-workflow runs */}
      <SubWorkflowLinks value={subWorkflowLinks}>
        <div className="flex min-h-0 flex-1">
          <div className="flex min-w-0 flex-1 flex-col">
            <div className="relative min-h-0 flex-1">
              {view === 'timeline' && (
                <RunTimeline
                  tasks={progress?.tasks}
                  switcher={<ViewSwitch view={view} onChange={setView} />}
                />
              )}
              {view === 'graph' && (
                <WorkflowCanvas
                  graph={graph}
                  tasksByNode={tasksByNode}
                  stageView={stages.stageView}
                  onNodeClick={setOpenNode}
                  follow={follow && followable}
                  find={findQuery}
                />
              )}
              {/* The canvas toolbar; its gaps pass the pointer through to the canvas */}
              {view === 'graph' && (
                <div className="pointer-events-none absolute inset-x-3 top-3 z-10 flex flex-wrap items-center gap-2">
                  <ViewSwitch
                    view={view}
                    onChange={setView}
                    className="pointer-events-auto"
                  />
                  <input
                    type="search"
                    aria-label="Find node on canvas"
                    placeholder="Find node"
                    value={findQuery}
                    onChange={(event) => setFindQuery(event.target.value)}
                    className="border-border bg-surface text-body-md shadow-card placeholder:text-on-surface-variant focus-visible:ring-primary/40 pointer-events-auto h-8 w-60 rounded-full border px-3 outline-none focus-visible:ring-2"
                  />
                  {stages.hasStages && (
                    <GroupingSwitch
                      grouped={stages.grouped}
                      onChange={stages.setGrouped}
                      className="pointer-events-auto"
                    />
                  )}
                  {followable && (
                    <button
                      type="button"
                      aria-pressed={follow}
                      onClick={() => setFollow((value) => !value)}
                      className={cn(
                        'border-border bg-surface text-label-sm shadow-card pointer-events-auto ml-auto rounded-lg border px-2.5 py-1',
                        follow
                          ? 'bg-accent text-accent-foreground'
                          : 'text-on-surface-variant hover:text-on-surface'
                      )}
                    >
                      Follow running nodes
                    </button>
                  )}
                </div>
              )}
            </div>
            <LogsPanel
              sessionId={sessionId}
              executionId={selectedExecutionId}
              entries={logs.entries}
              dropped={logs.dropped}
              isStreaming={isStreaming || logs.isStreaming}
              progress={progress}
            />
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
      </SubWorkflowLinks>
    </div>
  );
}
