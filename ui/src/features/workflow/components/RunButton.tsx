// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useRef, useState } from 'react';
import { RequirePermission } from '@/components/common/RequirePermission';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import { buttonVariants } from '@/components/ui/button';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { newIdempotencyKey } from '@/lib/utils';
import {
  controlErrorMessage,
  useExecutionControl,
} from '../hooks/useExecutionControl';

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

/** Run with an optional trigger payload; one idempotency key per dialog, so a retry is one run. */
export function RunButton({
  sessionId,
  workflowId,
  onStarted,
  size,
  label = 'Run workflow',
}: {
  sessionId: string;
  workflowId: string;
  onStarted: (executionId: string) => void;
  size?: 'sm';
  /** The button's accessible name, e.g. "Run check" in a table of workflows. */
  label?: string;
}) {
  const { start } = useExecutionControl(sessionId, workflowId);
  const [open, setOpen] = useState(false);
  const runKey = useRef('');
  return (
    <>
      <RequirePermission perm="startRuns">
        <button
          type="button"
          aria-label={label}
          className={buttonVariants({ variant: 'outline', size })}
          onClick={() => {
            runKey.current = newIdempotencyKey();
            setOpen(true);
          }}
        >
          <span aria-hidden="true" className="material-symbols-outlined text-base">
            play_arrow
          </span>
          Run
        </button>
      </RequirePermission>
      {open && (
        <RunDialog
          pending={start.isPending}
          error={start.error ? controlErrorMessage(start.error) : null}
          onClose={() => setOpen(false)}
          onSubmit={(payload) =>
            start.mutate(
              { payload, idempotencyKey: runKey.current },
              {
                onSuccess: (result) => {
                  setOpen(false);
                  onStarted(result.executionId);
                },
              }
            )
          }
        />
      )}
    </>
  );
}
