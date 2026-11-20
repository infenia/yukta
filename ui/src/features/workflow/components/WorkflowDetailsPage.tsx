// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { DeleteWorkflowDialog } from '@/features/sessions/components/DeleteDialogs';
import { RequirePermission } from '@/components/common/RequirePermission';
import {
  DropdownMenuItem,
  DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';
import { useMemo, useState } from 'react';
import { cn } from '@/lib/utils';
import {
  TOOLBAR_DIVIDER,
  TOOLBAR_GROUP,
  iconButton,
  toolbarSegment,
} from '@/components/ui/iconTone';
import { OverflowMenu } from '@/components/common/OverflowMenu';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { InlineError } from '@/components/common/InlineError';
import {
  controlErrorMessage,
  useExecutionControl,
} from '../hooks/useExecutionControl';
import type { NodeCommand } from './NodeControlsMenu';
import { RunControls } from './RunControls';
import { FindNodes } from './FindNodes';
import { IconButton } from '@/components/common/IconButton';
import { LogsPanel } from './LogsPanel';
import { AgentsPanel } from './agents/AgentsPanel';
import { useAgentStream } from '../hooks/useAgentStream';
import { AgentsByNode, byNode } from '../lib/agentsByNode';
import { InputsPanel, useInputQueries } from './InputsPanel';
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
  NodeOwnerOpener,
  NodeSupervisors,
  useNodeSupervisors,
} from '../hooks/useNodeSupervisors';
import {
  SubWorkflowLinks,
  useSubWorkflowLinks,
} from '../hooks/useSubWorkflowLinks';
import { useQuery } from '@tanstack/react-query';
import { getWorkflowProgress } from '../api/getWorkflowProgress';
import type { TaskProgress } from '../types/workflow';
import { NodeSheet, type SheetTab } from './NodeSheet';
import { PanelResizer } from './PanelResizer';
import { useExitValue } from '../hooks/useExitValue';
import { NodeStopDialog } from './NodeStopDialog';
import { StateMessage } from '@/components/common/StateMessage';
import { BreakpointDialog } from './BreakpointDialog';
import { WorkflowSupervision } from '@/features/supervisors/components/WorkflowSupervision';
import { Tip } from '@/components/ui/tooltip';

/** A switch inside the shared tool panel: stacked, its own chrome removed. */
const BARE_COLUMN =
  'flex-col rounded-full border-0 bg-transparent p-0 shadow-none backdrop-blur-none';

/** The workflow page: the run, its tasks and logs, and the controls. */

export function WorkflowDetailsPage() {
  const { sessionId = '', workflowId = '' } = useParams<{
    sessionId: string;
    workflowId: string;
  }>();
  const [panelOpen, setPanelOpen] = useState(true);
  // the right-hand panel's width, dragged by its left edge
  const [panelWidth, setPanelWidth] = useState(380);
  const [deleting, setDeleting] = useState(false);
  const [versionsOpen, setVersionsOpen] = useState(false);
  const [stopping, setStopping] = useState<string | null>(null);
  // The logs dock shows only this node's lines; set from its picker or the node sheet
  const [logNode, setLogNode] = useState<string | null>(null);
  // Clicking a node on the canvas opens its sheet; the node is in the URL so the view can be shared
  const [searchParams, setSearchParams] = useSearchParams();
  const openNode = searchParams.get('node');
  // the drawer's tab is in the URL too: a node's owner icon opens it on the Supervisor tab
  const sheetTab: SheetTab =
    searchParams.get('tab') === 'supervisor' ? 'supervisor' : 'node';
  const setOpenNode = (
    nodeId: string | null,
    tab: SheetTab = 'node',
    also?: (next: URLSearchParams) => void
  ) => {
    // the drawer lives in the progress panel: opening a node shows the panel again
    if (nodeId) setPanelOpen(true);
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (nodeId) {
          next.set('node', nodeId);
        } else {
          next.delete('node');
        }
        if (nodeId && tab === 'supervisor') {
          next.set('tab', tab);
        } else {
          next.delete('tab');
        }
        also?.(next);
        return next;
      },
      { replace: true }
    );
  };
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
  const [agentFocus, setAgentFocus] = useState<{
    id: string;
    nonce: number;
  } | null>(null);
  const agents = useAgentStream(
    selectedExecutionId,
    !isTerminalExecStatus(progress?.status)
  );
  const agentNodes = useMemo(
    () => byNode(agents.conversations),
    [agents.conversations]
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
  // both drawers stay mounted for their exit animation
  const panel = useExitValue(panelOpen ? true : null);
  const sheet = useExitValue(openGraphNode ?? null);
  // a const keeps the narrowing inside the tab callback below
  const shownNode = sheet.shown;
  const live = !isTerminalExecStatus(progress?.status);
  const inputQueries = useInputQueries(sessionId, workflowId, live);
  // no tab until the workflow has asked once, then it stays to say when all are answered
  const [inputsSeen, setInputsSeen] = useState(false);
  const inputsAsked =
    (inputQueries.open.data?.length ?? 0) +
      (inputQueries.history.data?.length ?? 0) >
      0 ||
    inputQueries.open.isError ||
    inputQueries.history.isError;
  if (inputsAsked && !inputsSeen) setInputsSeen(true);
  // Rollback is blocked while any run of this workflow is going, not only the one on screen: the
  // shown run's live status comes from its stream, the others from the executions list
  const anyRunLive =
    live ||
    executions.some(
      (run) =>
        run.executionId !== selectedExecutionId &&
        !isTerminalExecStatus(run.status)
    );
  const [follow, setFollow] = useState(true);
  const [findQuery, setFindQuery] = useState('');
  const [centerOn, setCenterOn] = useState<{ id: string; nonce: number }>();
  // only a run that is going has running nodes to follow
  const followable = progress !== null && live;
  const subWorkflowLinks = useSubWorkflowLinks(
    sessionId,
    graph,
    selectedExecutionId,
    live
  );
  const nodeSupervisors = useNodeSupervisors(sessionId, workflowId);
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
        leading={
          parent.data && (
            <Tip label="Parent run">
              <Link
                to={`/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(parent.data.workflowId)}?exec=${encodeURIComponent(parent.data.executionId)}`}
                aria-label="Parent run"
                className={iconButton('primary')}
              >
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-base"
                >
                  north_west
                </span>
              </Link>
            </Tip>
          )
        }
        meta={
          <WorkflowSupervision
            sessionId={sessionId}
            workflowId={workflowId}
            executionId={selectedExecutionId}
          />
        }
        runGroup={
          <RunHistoryDropdown
            executions={executions}
            selectedExecutionId={selectedExecutionId}
            onSelect={selectExecution}
          />
        }
        controls={
          <RunControls
            sessionId={sessionId}
            workflowId={workflowId}
            executionId={selectedExecutionId}
            status={executionStatus}
            onStarted={selectExecution}
          />
        }
        actions={
          <div
            role="group"
            aria-label="Workflow tools"
            className={TOOLBAR_GROUP}
          >
            <Tip label="Edit workflow">
              <Link
                to={`/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflowId)}/edit`}
                aria-label="Edit"
                className={toolbarSegment('neutral')}
              >
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-lg"
                >
                  edit_square
                </span>
              </Link>
            </Tip>
            <Tip
              label={panelOpen ? 'Hide progress panel' : 'Show progress panel'}
            >
              <button
                type="button"
                onClick={() => setPanelOpen((value) => !value)}
                aria-pressed={panelOpen}
                aria-label="Workflow progress"
                className={toolbarSegment(panelOpen ? 'primary' : 'neutral')}
              >
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-lg"
                >
                  {panelOpen ? 'right_panel_close' : 'right_panel_open'}
                </span>
              </button>
            </Tip>
            <span aria-hidden="true" className={TOOLBAR_DIVIDER} />
            <OverflowMenu
              label="Workflow actions"
              className={toolbarSegment('neutral')}
            >
              <DropdownMenuItem
                onClick={() => setVersionsOpen((value) => !value)}
              >
                {versionsOpen ? 'Hide versions' : 'Versions'}
              </DropdownMenuItem>
              {selectedExecutionId && (
                <DropdownMenuItem
                  onClick={() =>
                    void navigate(
                      `/control?tab=audit&execution=${encodeURIComponent(selectedExecutionId)}`
                    )
                  }
                >
                  Control commands of this run
                </DropdownMenuItem>
              )}
              <DropdownMenuSeparator />
              <RequirePermission perm="deleteSessions">
                <DropdownMenuItem
                  variant="destructive"
                  onClick={() => setDeleting(true)}
                >
                  Delete
                </DropdownMenuItem>
              </RequirePermission>
            </OverflowMenu>
          </div>
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
          liveRun={anyRunLive}
        />
      )}

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

      {/* The workspace fills the rest of the screen: the canvas over the logs dock, the progress
          panel beside both, so a 100-node run no longer stretches the page */}
      {/* The canvas nodes and the failure callout both open sub-workflow runs */}
      <NodeSupervisors value={nodeSupervisors}>
        <NodeOwnerOpener value={(nodeId) => setOpenNode(nodeId, 'supervisor')}>
          <SubWorkflowLinks value={subWorkflowLinks}>
            <div className="flex min-h-0 flex-1">
              <div className="flex min-w-0 flex-1 flex-col">
                <div className="relative min-h-0 flex-1">
                  {view === 'timeline' && (
                    <RunTimeline
                      tasks={progress?.tasks}
                      graph={graph}
                      switcher={<ViewSwitch view={view} onChange={setView} />}
                    />
                  )}
                  {view === 'graph' && (
                    <AgentsByNode.Provider value={agentNodes}>
                      <WorkflowCanvas
                        graph={graph}
                        tasksByNode={tasksByNode}
                        stageView={stages.stageView}
                        onNodeClick={(nodeId) => {
                          setOpenNode(nodeId);
                          // a node that runs an agent also brings up its conversation
                          const agent = agentNodes[nodeId];
                          if (agent) {
                            setAgentFocus((held) => ({
                              id: agent.id,
                              nonce: (held?.nonce ?? 0) + 1,
                            }));
                          }
                        }}
                        follow={follow && followable}
                        find={findQuery}
                        centerOn={centerOn}
                        tools={
                          <div
                            role="toolbar"
                            aria-orientation="vertical"
                            aria-label="Canvas tools"
                            className="border-border bg-card/80 shadow-card pointer-events-auto flex flex-col items-center gap-1 rounded-full border p-1 backdrop-blur-sm"
                          >
                            <FindNodes
                              graph={graph}
                              query={findQuery}
                              onQuery={setFindQuery}
                              onPick={(nodeId) => {
                                setOpenNode(
                                  nodeId,
                                  'node',
                                  stages.revealParams(nodeId)
                                );
                                setCenterOn((held) => ({
                                  id: nodeId,
                                  nonce: (held?.nonce ?? 0) + 1,
                                }));
                              }}
                            />
                            <span
                              aria-hidden="true"
                              className="bg-border h-px w-5"
                            />
                            <ViewSwitch
                              view={view}
                              onChange={setView}
                              className={BARE_COLUMN}
                            />
                            {stages.hasStages && (
                              <GroupingSwitch
                                grouped={stages.grouped}
                                onChange={stages.setGrouped}
                                className={BARE_COLUMN}
                              />
                            )}
                            {followable && (
                              <IconButton
                                icon="my_location"
                                label="Follow running nodes"
                                aria-pressed={follow}
                                onClick={() => setFollow((value) => !value)}
                                tone={follow ? 'primary' : 'neutral'}
                                className={cn(
                                  'size-8 rounded-full border-0',
                                  follow && 'bg-primary/10'
                                )}
                              />
                            )}
                          </div>
                        }
                      />
                    </AgentsByNode.Provider>
                  )}
                </div>
                <LogsPanel
                  inputs={
                    inputsSeen
                      ? {
                          waiting: inputQueries.open.data?.length ?? 0,
                          content: (
                            <InputsPanel
                              sessionId={sessionId}
                              workflowId={workflowId}
                              live={live}
                            />
                          ),
                        }
                      : null
                  }
                  agents={{
                    waiting: agents.waiting,
                    focus: agentFocus,
                    content: (
                      <AgentsPanel
                        focus={agentFocus}
                        conversations={agents.conversations}
                        items={agents.items}
                      />
                    ),
                  }}
                  sessionId={sessionId}
                  executionId={selectedExecutionId}
                  entries={logs.entries}
                  dropped={logs.dropped}
                  isStreaming={isStreaming || logs.isStreaming}
                  progress={progress}
                  node={logNode}
                  nodes={graph.nodes.map((n) => n.nodeId)}
                  onNode={setLogNode}
                />
              </div>
              {panel.shown && (
                // the node drawer opens over the panel, in its box, and goes when it does
                <div
                  className={cn(
                    'relative flex shrink-0 duration-150',
                    panel.closing &&
                      'animate-out fade-out-0 fill-mode-forwards slide-out-to-right-4'
                  )}
                  style={{ width: panelWidth }}
                >
                  <PanelResizer
                    width={panelWidth}
                    min={320}
                    max={Math.max(320, Math.round(window.innerWidth * 0.7))}
                    onWidth={setPanelWidth}
                  />
                  <ProgressPanel
                    graph={graph}
                    progress={progress}
                    tasksByNode={tasksByNode}
                    sessionId={sessionId}
                    onClose={() => setPanelOpen(false)}
                    onNodeCommand={
                      selectedExecutionId ? onNodeCommand : undefined
                    }
                  />
                  {shownNode && (
                    <NodeSheet
                      node={shownNode}
                      closing={sheet.closing}
                      task={tasksByNode[shownNode.nodeId]}
                      sessionId={sessionId}
                      workflowId={workflowId}
                      executionId={selectedExecutionId}
                      tab={sheetTab}
                      onTab={(tab) => setOpenNode(shownNode.nodeId, tab)}
                      onCommand={onNodeCommand}
                      onShowLogs={setLogNode}
                      onClose={() => setOpenNode(null)}
                    />
                  )}
                </div>
              )}
            </div>
          </SubWorkflowLinks>
        </NodeOwnerOpener>
      </NodeSupervisors>
    </div>
  );
}
