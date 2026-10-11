// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { buttonVariants } from '@/components/ui/button';
import { useState } from 'react';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { StopMode } from '../api/executionControl';
import { controlErrorMessage, useExecutionControl } from '../hooks/useExecutionControl';
import { isTerminalExecStatus, toExecStatusCategory } from '../lib/statusMapper';

const BUTTON = buttonVariants({ variant: 'outline' });

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
      setParseError(e instanceof SyntaxError ? 'Payload is not valid JSON' : (e as Error).message);
    }
  };

  return (
    <Modal title="Run workflow" onClose={onClose}>
      <label className="mb-1 block text-sm text-on-surface" htmlFor="run-payload">
        Trigger payload (JSON, optional)
      </label>
      <textarea
        id="run-payload"
        value={text}
        onChange={(event) => setText(event.target.value)}
        rows={8}
        spellCheck={false}
        placeholder='{"key": "value"}'
        className="mb-3 w-full rounded-lg border border-outline-variant bg-surface-container-low p-2 font-mono text-xs text-on-surface"
      />
      <InlineError message={parseError ?? error} />
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" className={BUTTON} onClick={onClose}>
          Cancel
        </button>
        <button type="button" className={BUTTON} onClick={submit} disabled={pending}>
          Run
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
  const [confirm, setConfirm] = useState<{ label: string; mode: StopMode | null } | null>(null);

  const category = toExecStatusCategory(status);
  const live = category === 'running' || category === 'paused' || category === 'pending';
  const canRestart = executionId !== null && isTerminalExecStatus(status);
  const failure = [control.runAction, control.stopAll, control.start]
    .map((mutation) => mutation.error)
    .find(Boolean);

  const act = (action: 'pause' | 'resume' | 'restart') =>
    executionId &&
    control.runAction.mutate(
      { executionId, action },
      { onSuccess: (result) => action === 'restart' && onStarted(result.executionId) }
    );

  // The stop menu is only enabled for a live execution, so executionId is set here.
  const doStop = (mode: StopMode | null) => {
    if (mode) control.stopAll.mutate(mode);
    else control.runAction.mutate({ executionId: executionId as string, action: 'stop' });
    setConfirm(null);
  };

  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="run-controls">
      <button type="button" className={BUTTON} onClick={() => setRunning(true)}>
        <span className="material-symbols-outlined text-base">play_arrow</span>Run
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
        <span className="material-symbols-outlined text-base">play_circle</span>Resume
      </button>
      <DropdownMenu>
        <DropdownMenuTrigger className={BUTTON} disabled={!live} aria-label="Stop options">
          <span className="material-symbols-outlined text-base">stop</span>Stop
          <span className="material-symbols-outlined text-base">arrow_drop_down</span>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onClick={() => setConfirm({ label: 'Stop this run', mode: null })}>
            Stop this run
          </DropdownMenuItem>
          {(['DRAIN', 'CANCEL', 'KILL'] as const).map((mode) => (
            <DropdownMenuItem
              key={mode}
              onClick={() => setConfirm({ label: `${mode} all runs`, mode })}
            >
              {mode === 'DRAIN' ? 'Drain' : mode === 'CANCEL' ? 'Cancel' : 'Kill'} all runs
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
      <button type="button" className={BUTTON} disabled={!canRestart} onClick={() => act('restart')}>
        <span className="material-symbols-outlined text-base">restart_alt</span>Restart
      </button>
      <InlineError message={failure ? controlErrorMessage(failure) : null} />

      {running && (
        <RunDialog
          pending={control.start.isPending}
          error={control.start.error ? controlErrorMessage(control.start.error) : null}
          onClose={() => setRunning(false)}
          onSubmit={(payload) =>
            control.start.mutate(payload, {
              onSuccess: (result) => {
                setRunning(false);
                onStarted(result.executionId);
              },
            })
          }
        />
      )}
      {confirm && (
        <Modal title={`${confirm.label}?`} onClose={() => setConfirm(null)}>
          <p className="mb-4 text-sm text-on-surface-variant">
            This cannot be undone. A stopped run can only be restarted.
          </p>
          <div className="flex justify-end gap-2">
            <button type="button" className={BUTTON} onClick={() => setConfirm(null)}>
              Cancel
            </button>
            <button type="button" className={BUTTON} onClick={() => doStop(confirm.mode)}>
              Confirm
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
