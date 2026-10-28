// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { buttonVariants } from '@/components/ui/button';
import { useRef, useState } from 'react';
import { newIdempotencyKey } from '@/lib/utils';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { StopMode } from '../api/executionControl';
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

/** Priorities the scheduler accepts from the UI. */
export const MIN_PRIORITY = -100;
export const MAX_PRIORITY = 100;

/** Parses the payload editor text; empty means no payload. */
export function parsePayload(text: string): Record<string, unknown> {
  if (!text.trim()) return {};
  const value: unknown = JSON.parse(text);
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Payload must be a JSON object');
  }
  return value as Record<string, unknown>;
}

function RunDialog({
  onSubmit,
  onClose,
  pending,
  error,
}: {
  onSubmit: (payload: Record<string, unknown>) => void;
  onClose: () => void;
  pending: boolean;
  error: string | null;
}) {
  const [text, setText] = useState('');
  const [parseError, setParseError] = useState<string | null>(null);

  const submit = () => {
    try {
      onSubmit(parsePayload(text));
      setParseError(null);
    } catch (e) {
      setParseError(
        e instanceof SyntaxError
          ? 'Payload is not valid JSON'
          : (e as Error).message
      );
    }
  };

  return (
    <Modal title="Run workflow" onClose={onClose}>
      <label
        className="text-body-md text-on-surface mb-1 block"
        htmlFor="run-payload"
      >
        Trigger payload (JSON, optional)
      </label>
      <textarea
        id="run-payload"
        value={text}
        onChange={(event) => setText(event.target.value)}
        rows={8}
        spellCheck={false}
        placeholder='{"key": "value"}'
        className={`${textareaFieldClassName} text-code-sm mb-3`}
      />
      <InlineError message={parseError ?? error} />
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" className={BUTTON} onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className={BUTTON}
          onClick={submit}
          disabled={pending}
        >
          Run
        </button>
      </div>
    </Modal>
  );
}

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
  const [running, setRunning] = useState(false);
  const runKey = useRef('');
  const [prioritizing, setPrioritizing] = useState(false);
  const [recovering, setRecovering] = useState(false);
  const [confirm, setConfirm] = useState<{
    label: string;
    mode: StopMode | null;
  } | null>(null);

  const category = toExecStatusCategory(status);
  const live =
    category === 'running' || category === 'paused' || category === 'pending';
  const canRestart = executionId !== null && isTerminalExecStatus(status);
  const canPrioritize = executionId !== null && isQueuedExecStatus(status);
  const canRecover = executionId !== null && isAbortedExecStatus(status);
  const failure = [
    control.priority,
    control.runAction,
    control.stopAll,
    control.start,
    control.recover,
  ]
    .map((mutation) => mutation.error)
    .find(Boolean);

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
  const doStop = (mode: StopMode | null) => {
    if (mode) control.stopAll.mutate(mode);
    else
      control.runAction.mutate({
        executionId: executionId as string,
        action: 'stop',
      });
    setConfirm(null);
  };

  return (
    <div
      className="flex flex-wrap items-center gap-2"
      data-testid="run-controls"
    >
      <button
        type="button"
        className={BUTTON}
        onClick={() => {
          runKey.current = newIdempotencyKey();
          setRunning(true);
        }}
      >
        <span className="material-symbols-outlined text-base">play_arrow</span>
        Run
      </button>
      <button
        type="button"
        className={BUTTON}
        disabled={category !== 'running'}
        onClick={() => act('pause')}
      >
        <span className="material-symbols-outlined text-base">pause</span>Pause
      </button>
      <button
        type="button"
        className={BUTTON}
        disabled={category !== 'paused'}
        onClick={() => act('resume')}
      >
        <span className="material-symbols-outlined text-base">play_circle</span>
        Resume
      </button>
      <DropdownMenu>
        <DropdownMenuTrigger
          className={BUTTON}
          disabled={!live}
          aria-label="Stop options"
        >
          <span className="material-symbols-outlined text-base">stop</span>Stop
          <span className="material-symbols-outlined text-base">
            arrow_drop_down
          </span>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem
            onClick={() => setConfirm({ label: 'Stop this run', mode: null })}
          >
            Stop this run
          </DropdownMenuItem>
          {(['DRAIN', 'CANCEL', 'KILL'] as const).map((mode) => (
            <DropdownMenuItem
              key={mode}
              onClick={() => setConfirm({ label: `${mode} all runs`, mode })}
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
      <button
        type="button"
        className={BUTTON}
        disabled={!canRestart}
        onClick={() => act('restart')}
      >
        <span className="material-symbols-outlined text-base">restart_alt</span>
        Restart
      </button>
      {canPrioritize && (
        <button
          type="button"
          className={BUTTON}
          onClick={() => setPrioritizing(true)}
        >
          <span className="material-symbols-outlined text-base">
            low_priority
          </span>
          Priority…
        </button>
      )}
      {canRecover && (
        <button
          type="button"
          className={BUTTON}
          disabled={control.recover.isPending}
          onClick={() => setRecovering(true)}
        >
          <span className="material-symbols-outlined text-base">history</span>
          Recover
        </button>
      )}
      <InlineError message={failure ? controlErrorMessage(failure) : null} />

      {running && (
        <RunDialog
          pending={control.start.isPending}
          error={
            control.start.error
              ? controlErrorMessage(control.start.error)
              : null
          }
          onClose={() => setRunning(false)}
          onSubmit={(payload) =>
            control.start.mutate(
              { payload, idempotencyKey: runKey.current },
              {
                onSuccess: (result) => {
                  setRunning(false);
                  onStarted(result.executionId);
                },
              }
            )
          }
        />
      )}
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
      {recovering && (
        <Modal title="Recover this run?" onClose={() => setRecovering(false)}>
          <p className="text-body-md text-on-surface-variant mb-4">
            Continue this run as a new run? Finished nodes are replayed.
          </p>
          <div className="flex justify-end gap-2">
            <button
              type="button"
              className={BUTTON}
              onClick={() => setRecovering(false)}
            >
              Cancel
            </button>
            <button
              type="button"
              className={BUTTON}
              onClick={() => {
                setRecovering(false);
                control.recover.mutate(executionId as string, {
                  onSuccess: (result) => onStarted(result.executionId),
                });
              }}
            >
              Recover
            </button>
          </div>
        </Modal>
      )}
      {confirm && (
        <Modal title={`${confirm.label}?`} onClose={() => setConfirm(null)}>
          <p className="text-body-md text-on-surface-variant mb-4">
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
              onClick={() => doStop(confirm.mode)}
            >
              Confirm
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
