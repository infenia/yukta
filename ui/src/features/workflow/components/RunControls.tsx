// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { RequireControl } from '@/features/supervisors/components/RequireControl';
import { buttonVariants } from '@/components/ui/button';
import {
  TOOLBAR_DIVIDER,
  TOOLBAR_GROUP,
  toolbarSegment,
} from '@/components/ui/iconTone';
import { useState } from 'react';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { StopMode } from '../api/executionControl';
import { cn } from '@/lib/utils';
import { copyIcon, copyTone, useCopyCommand } from '../hooks/useCopyCommand';
import { useWorkflowExecutions } from '../hooks/useWorkflowExecutions';
import { CopyAsMenu, type CopyChoice } from './CopyAsMenu';
import { RunButton } from './RunButton';
import { Tip } from '@/components/ui/tooltip';

import { RecoverButton } from './RecoverButton';
import {
  controlErrorMessage,
  useExecutionControl,
} from '../hooks/useExecutionControl';
import {
  isQueuedExecStatus,
  isAbortedExecStatus,
  isTerminalExecStatus,
  toExecStatusCategory,
} from '../lib/statusMapper';

const BUTTON = buttonVariants({ variant: 'outline' });
/** An icon glyph inside a toolbar segment. */
const GLYPH = 'material-symbols-outlined text-lg';

/** An icon-only segment of the run controls: the label is the tooltip and the accessible name. */
function ControlButton({
  icon,
  label,
  tone,
  disabled,
  title,
  onClick,
}: {
  icon: string;
  label: string;
  tone: Parameters<typeof toolbarSegment>[0];
  disabled?: boolean;
  /** Set by RequireControl (the reason it is disabled); defaults to the label. */
  title?: string;
  onClick: () => void;
}) {
  return (
    <Tip label={label} title={title} disabled={disabled}>
      <button
        type="button"
        className={toolbarSegment(tone)}
        aria-label={label}
        disabled={disabled}
        onClick={onClick}
      >
        <span aria-hidden="true" className={GLYPH}>
          {icon}
        </span>
      </button>
    </Tip>
  );
}

/** Priorities the scheduler accepts from the UI. */
export const MIN_PRIORITY = -100;
export const MAX_PRIORITY = 100;

function PriorityDialog({
  onSubmit,
  onClose,
  pending,
}: {
  onSubmit: (priority: number) => void;
  onClose: () => void;
  pending: boolean;
}) {
  const [text, setText] = useState('0');
  const value = Number(text);
  const valid =
    text.trim() !== '' &&
    Number.isInteger(value) &&
    value >= MIN_PRIORITY &&
    value <= MAX_PRIORITY;

  return (
    <Modal title="Change priority" onClose={onClose}>
      <label
        className="text-body-md text-on-surface mb-1 block"
        htmlFor="run-priority"
      >
        Priority ({MIN_PRIORITY} to {MAX_PRIORITY}, higher runs first)
      </label>
      <input
        id="run-priority"
        type="number"
        step={1}
        min={MIN_PRIORITY}
        max={MAX_PRIORITY}
        value={text}
        onChange={(event) => setText(event.target.value)}
        className="border-border bg-background text-body-md mb-3 w-full rounded-lg border px-3 py-2"
      />
      <InlineError
        message={
          valid
            ? null
            : `Enter a whole number from ${MIN_PRIORITY} to ${MAX_PRIORITY}`
        }
      />
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" className={BUTTON} onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className={BUTTON}
          disabled={!valid || pending}
          onClick={() => onSubmit(value)}
        >
          Apply
        </button>
      </div>
    </Modal>
  );
}

/**
 * Run / pause / resume / stop / restart for the selected execution. Buttons enable according
 * to the execution status; destructive stops ask for confirmation first.
 */
export function RunControls({
  sessionId,
  workflowId,
  executionId,
  status,
  onStarted,
}: {
  sessionId: string;
  workflowId: string;
  executionId: string | null;
  status: string | null;
  onStarted: (executionId: string) => void;
}) {
  const control = useExecutionControl(sessionId, workflowId);
  const { status: copied, copy } = useCopyCommand();
  const [prioritizing, setPrioritizing] = useState(false);
  // scope 'run' stops the run on screen; 'all' every active run of the workflow
  const [confirm, setConfirm] = useState<{
    label: string;
    scope: 'run' | 'all';
    mode?: StopMode;
  } | null>(null);
  const executions = useWorkflowExecutions(sessionId, workflowId);
  const activeRuns = executions.data?.filter(
    (run) => !isTerminalExecStatus(run.status)
  ).length;

  const category = toExecStatusCategory(status);
  const live =
    category === 'running' || category === 'paused' || category === 'pending';
  const canRestart = executionId !== null && isTerminalExecStatus(status);
  const canPrioritize = executionId !== null && isQueuedExecStatus(status);
  const canRecover = executionId !== null && isAbortedExecStatus(status);
  const failure = [control.priority, control.runAction, control.stopAll]
    .map((mutation) => mutation.error)
    .find(Boolean);

  // The actions the buttons above offer right now, as commands for the other clients.
  const run = (verb: 'pause' | 'resume' | 'stop' | 'restart', label: string) =>
    executionId
      ? [
          {
            label,
            action: { kind: 'run', verb, sessionId, executionId },
          } as const,
        ]
      : [];
  const choices: CopyChoice[] = [
    { label: 'Run workflow', action: { kind: 'start', sessionId, workflowId } },
    ...(category === 'running' ? run('pause', 'Pause run') : []),
    ...(category === 'paused' ? run('resume', 'Resume run') : []),
    ...(live ? run('stop', 'Stop this run') : []),
    ...(canRestart ? run('restart', 'Restart run') : []),
    ...(['DRAIN', 'CANCEL', 'KILL'] as const).map((mode) => ({
      label: `Stop all runs (${mode})`,
      action: { kind: 'stopAll', sessionId, workflowId, mode } as const,
    })),
  ];

  const act = (action: 'pause' | 'resume' | 'restart') =>
    executionId &&
    control.runAction.mutate(
      { executionId, action },
      {
        onSuccess: (result) =>
          action === 'restart' && onStarted(result.executionId),
      }
    );

  // The stop menu is only enabled for a live execution, so executionId is set here.
  const doStop = (choice: NonNullable<typeof confirm>) => {
    if (choice.scope === 'all') control.stopAll.mutate(choice.mode as StopMode);
    else
      control.runAction.mutate({
        executionId: executionId as string,
        action: 'stop',
        mode: choice.mode,
      });
    setConfirm(null);
  };

  return (
    <div
      className="flex flex-wrap items-center gap-2"
      data-testid="run-controls"
    >
      {/* Run, Pause or Resume, Stop and Restart, then the run's tools, as one segmented pill */}
      <div role="group" aria-label="Run controls" className={TOOLBAR_GROUP}>
        {category === 'paused' ? (
          <RequireControl
            perm="controlRuns"
            command="resume"
            target={{ execution: executionId }}
          >
            <ControlButton
              icon="play_arrow"
              label="Resume"
              tone="success"
              onClick={() => act('resume')}
            />
          </RequireControl>
        ) : (
          <RunButton
            sessionId={sessionId}
            workflowId={workflowId}
            onStarted={onStarted}
            className={cn(toolbarSegment('success'), '[&>span]:text-lg')}
            iconOnly
            disabled={live}
          />
        )}
        <RequireControl
          perm="controlRuns"
          command="pause"
          target={{ execution: executionId }}
        >
          <ControlButton
            icon="pause"
            label="Pause"
            tone="attention"
            disabled={category !== 'running'}
            onClick={() => act('pause')}
          />
        </RequireControl>
        <DropdownMenu>
          <RequireControl
            perm="controlRuns"
            command="stop"
            target={{ execution: executionId }}
          >
            <Tip label="Stop" disabled={!live}>
              <DropdownMenuTrigger
                className={cn(toolbarSegment('destructive'), 'pr-1')}
                aria-label="Stop options"
              >
                <span aria-hidden="true" className={GLYPH}>
                  stop
                </span>
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined -ml-0.5 text-sm opacity-70"
                >
                  expand_more
                </span>
              </DropdownMenuTrigger>
            </Tip>
          </RequireControl>
          <DropdownMenuContent align="end" className="w-max">
            <DropdownMenuItem
              onClick={() =>
                setConfirm({ label: 'Stop this run', scope: 'run' })
              }
            >
              Stop this run
            </DropdownMenuItem>
            {(['CANCEL', 'KILL'] as const).map((mode) => (
              <DropdownMenuItem
                key={mode}
                onClick={() =>
                  setConfirm({
                    label: `${mode === 'CANCEL' ? 'Cancel' : 'Kill'} this run`,
                    scope: 'run',
                    mode,
                  })
                }
              >
                {mode === 'CANCEL' ? 'Cancel' : 'Kill'} this run
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            {(['DRAIN', 'CANCEL', 'KILL'] as const).map((mode) => (
              <DropdownMenuItem
                key={mode}
                onClick={() =>
                  setConfirm({ label: `${mode} all runs`, scope: 'all', mode })
                }
              >
                {mode === 'DRAIN'
                  ? 'Drain'
                  : mode === 'CANCEL'
                    ? 'Cancel'
                    : 'Kill'}{' '}
                all runs
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
        <RequireControl
          perm="controlRuns"
          command="restart"
          target={{ execution: executionId }}
        >
          <ControlButton
            icon="replay"
            label="Restart"
            tone="primary"
            disabled={!canRestart}
            onClick={() => act('restart')}
          />
        </RequireControl>
        <span aria-hidden="true" className={TOOLBAR_DIVIDER} />
        {canPrioritize && (
          <RequireControl
            perm="controlRuns"
            command="priority"
            target={{ execution: executionId }}
          >
            <Tip label="Change priority">
              <button
                type="button"
                className={toolbarSegment('attention')}
                aria-label="Priority…"
                onClick={() => setPrioritizing(true)}
              >
                <span aria-hidden="true" className={GLYPH}>
                  low_priority
                </span>
              </button>
            </Tip>
          </RequireControl>
        )}
        <DropdownMenu>
          <Tip label="Copy as command…">
            <DropdownMenuTrigger
              className={cn(toolbarSegment('neutral'), copyTone(copied))}
              aria-label="Copy as"
            >
              <span aria-hidden="true" className={GLYPH}>
                {copyIcon(copied, 'terminal')}
              </span>
            </DropdownMenuTrigger>
          </Tip>
          <DropdownMenuContent align="end" className="w-max">
            <CopyAsMenu choices={choices} onCopy={copy} />
          </DropdownMenuContent>
        </DropdownMenu>
        {copied && (
          <span role="status" className="sr-only">
            {copied === 'copied' ? 'Copied' : 'Copy failed'}
          </span>
        )}
        {canRecover && (
          <RecoverButton
            sessionId={sessionId}
            workflowId={workflowId}
            executionId={executionId as string}
            onRecovered={onStarted}
            className={toolbarSegment('primary')}
            iconOnly
          />
        )}
      </div>
      <InlineError message={failure ? controlErrorMessage(failure) : null} />

      {prioritizing && (
        <PriorityDialog
          pending={control.priority.isPending}
          onClose={() => setPrioritizing(false)}
          onSubmit={(priority) => {
            setPrioritizing(false);
            control.priority.mutate({
              executionId: executionId as string,
              priority,
            });
          }}
        />
      )}
      {confirm && (
        <Modal title={`${confirm.label}?`} onClose={() => setConfirm(null)}>
          <p className="text-body-md text-on-surface-variant mb-4">
            {confirm.scope === 'all'
              ? `This stops ${
                  activeRuns === undefined
                    ? 'every active run'
                    : `${activeRuns} active ${activeRuns === 1 ? 'run' : 'runs'}`
                } of this workflow, including runs started by other people or by a schedule. `
              : 'This stops only the run on screen. '}
            This cannot be undone. A stopped run can only be restarted.
          </p>
          <div className="flex justify-end gap-2">
            <button
              type="button"
              className={BUTTON}
              onClick={() => setConfirm(null)}
            >
              Cancel
            </button>
            <button
              type="button"
              className={BUTTON}
              onClick={() => doStop(confirm)}
            >
              Confirm
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
