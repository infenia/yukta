// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useContext, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { Callout } from '@/components/common/Callout';
import { SubWorkflowLinks } from '../hooks/useSubWorkflowLinks';
import { isTerminalExecStatus, toNodeUiStatus } from '../lib/statusMapper';
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

/** The panel's width in px and whether it is folded to a rail; both are kept across runs and reloads. */
const WIDTH_KEY = 'yukta.progressWidth';
const COLLAPSED_KEY = 'yukta.progressCollapsed';
const DEFAULT_WIDTH = 380;
const MIN_WIDTH = 280;
/** What the canvas keeps when the panel is dragged as wide as it may go. */
const MIN_CANVAS = 320;
const KEY_STEP = 24;

/** The widest the panel may be dragged: its row less a usable canvas, or most of the window before layout. */
function maxWidth(panel: HTMLElement | null): number {
  const row = panel?.parentElement?.clientWidth ?? 0;
  return row > 0
    ? Math.max(MIN_WIDTH, row - MIN_CANVAS)
    : Math.round(window.innerWidth * 0.6);
}

function storedWidth(): number {
  const stored = Number(localStorage.getItem(WIDTH_KEY));
  return stored >= MIN_WIDTH ? stored : DEFAULT_WIDTH;
}

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
  const [width, setWidth] = useState(storedWidth);
  const [collapsed, setCollapsed] = useState(
    () => localStorage.getItem(COLLAPSED_KEY) === 'true'
  );
  const panelRef = useRef<HTMLElement>(null);
  useEffect(() => {
    localStorage.setItem(WIDTH_KEY, String(width));
  }, [width]);
  useEffect(() => {
    localStorage.setItem(COLLAPSED_KEY, String(collapsed));
  }, [collapsed]);
  const resize = (next: number) =>
    setWidth(
      Math.round(
        Math.min(Math.max(next, MIN_WIDTH), maxWidth(panelRef.current))
      )
    );
  // Dragging the left edge: window listeners, so the drag holds when the pointer leaves the handle
  const startDrag = (event: React.PointerEvent) => {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = panelRef.current?.offsetWidth || width;
    const move = (e: PointerEvent) => resize(startWidth + startX - e.clientX);
    const stop = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop);
  };
  const resizeByKey = (event: React.KeyboardEvent) => {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      resize(width + (event.key === 'ArrowLeft' ? KEY_STEP : -KEY_STEP));
    }
  };
  const finished =
    progress && isTerminalExecStatus(progress.status) ? progress : null;

  if (collapsed) {
    return (
      <aside
        ref={panelRef}
        className="border-border bg-background flex w-10 shrink-0 flex-col items-center gap-3 border-l py-3"
        data-testid="progress-panel"
        aria-label="Workflow progress"
      >
        <button
          type="button"
          onClick={() => setCollapsed(false)}
          aria-label="Expand workflow progress"
          aria-expanded={false}
          className="text-on-surface-variant transition-standard hover:bg-muted hover:text-on-surface flex size-7 items-center justify-center rounded-lg"
        >
          <span className="material-symbols-outlined rotate-180 text-lg">
            chevron_right
          </span>
        </button>
        <span className="text-label-md text-on-surface-variant [writing-mode:vertical-rl]">
          Workflow progress
        </span>
      </aside>
    );
  }
  return (
    <aside
      ref={panelRef}
      style={{ width }}
      className="border-border bg-background relative flex shrink-0 border-l"
      data-testid="progress-panel"
      aria-label="Workflow progress"
    >
      <div
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize the progress panel"
        aria-valuemin={MIN_WIDTH}
        aria-valuemax={maxWidth(panelRef.current)}
        aria-valuenow={width}
        tabIndex={0}
        onPointerDown={startDrag}
        onKeyDown={resizeByKey}
        className="hover:bg-primary/40 focus-visible:bg-primary/60 absolute inset-y-0 -left-1 z-10 w-2 cursor-col-resize touch-none focus-visible:outline-none"
      />
      <div className="flex min-w-0 flex-1 flex-col overflow-y-auto">
        <div className="flex items-center justify-between px-4 pt-4 pb-2">
          <h2 className="text-label-md text-on-surface">Workflow progress</h2>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => setCollapsed(true)}
              aria-label="Collapse workflow progress"
              aria-expanded={true}
              className="text-on-surface-variant transition-standard hover:bg-muted hover:text-on-surface flex size-7 items-center justify-center rounded-lg"
            >
              <span className="material-symbols-outlined text-lg">
                chevron_right
              </span>
            </button>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close workflow progress panel"
              className="text-on-surface-variant transition-standard hover:bg-muted hover:text-on-surface flex size-7 items-center justify-center rounded-lg"
            >
              <span className="material-symbols-outlined text-lg">close</span>
            </button>
          </div>
        </div>
        <div role="tablist" className="border-border flex gap-5 border-b px-4">
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
              className={`text-label-md -mb-px border-b-2 py-2 ${
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
                  graph={graph}
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
      </div>
    </aside>
  );
}
