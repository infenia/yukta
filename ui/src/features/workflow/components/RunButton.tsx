// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { RequireControl } from '@/features/supervisors/components/RequireControl';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import { buttonVariants } from '@/components/ui/button';
import { iconButton } from '@/components/ui/iconTone';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { newIdempotencyKey } from '@/lib/utils';
import {
  controlErrorMessage,
  useExecutionControl,
} from '../hooks/useExecutionControl';
import {
  buildPayload,
  modifierLabel,
  parsePayload,
  payloadSkeleton,
  payloadValues,
} from './RunButton.helpers';
import { getWorkflowDefinition } from '../api/getWorkflowDefinition';
import { startInputs, type StartInput } from '../lib/startValues';
import { Input } from '@/components/ui/input';
import { Tip } from '@/components/ui/tooltip';

const MOD = modifierLabel(navigator.userAgent);
const GHOST = buttonVariants({ variant: 'ghost' });
const PRIMARY = buttonVariants();

function FieldRow({
  input,
  value,
  onChange,
}: {
  input: StartInput;
  value: string;
  onChange: (value: string) => void;
}) {
  const id = `run-${input.path}`;
  const unset = value === '' && !input.optional;
  const title =
    value !== '' ? 'Set' : input.optional ? 'Optional' : 'Needed, not set';
  return (
    <div className="flex items-center gap-3">
      <span
        role="img"
        aria-label={title}
        title={title}
        className={`size-2.5 shrink-0 rounded-full border-2 ${
          value !== ''
            ? 'border-success bg-success'
            : unset
              ? 'border-warning'
              : 'border-outline-variant'
        }`}
      />
      <label
        htmlFor={id}
        className="text-body-md text-on-surface w-32 shrink-0 truncate font-mono"
      >
        {input.path}
      </label>
      {input.type === 'toggle' ? (
        <select
          id={id}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          className={`${textareaFieldClassName} h-9`}
        >
          <option value="">Not set</option>
          <option value="true">On</option>
          <option value="false">Off</option>
        </select>
      ) : (
        <Input
          id={id}
          type={input.type === 'number' ? 'number' : 'text'}
          placeholder={input.fallback}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </div>
  );
}

function RunDialog({
  inputs,
  onSubmit,
  onClose,
  pending,
  error,
}: {
  /** What the workflow reads from its start; null when its definition could not be read. */
  inputs: StartInput[] | null;
  onSubmit: (payload: Record<string, unknown>) => void;
  onClose: () => void;
  pending: boolean;
  error: string | null;
}) {
  const known = inputs ?? [];
  const [raw, setRaw] = useState(inputs === null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [text, setText] = useState('');
  const [parseError, setParseError] = useState<string | null>(null);
  const unset = known.filter(
    ({ path, optional }) => !optional && !values[path]
  ).length;

  const toggleRaw = () => {
    try {
      if (raw) {
        setValues(payloadValues(known, parsePayload(text)));
      } else {
        setText(payloadSkeleton(known, values));
      }
      setParseError(null);
      setRaw(!raw);
    } catch {
      setParseError('Payload is not valid JSON');
    }
  };

  const submit = () => {
    try {
      onSubmit(raw ? parsePayload(text) : buildPayload(known, values));
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
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (!pending) submit();
        }}
        onKeyDown={(event) => {
          if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
            event.preventDefault();
            if (!pending) submit();
          }
        }}
      >
        {raw ? (
          <textarea
            id="run-payload"
            aria-label="Trigger payload (JSON)"
            value={text}
            onChange={(event) => setText(event.target.value)}
            rows={8}
            spellCheck={false}
            placeholder='{"key": "value"}'
            className={`${textareaFieldClassName} text-code-sm mb-3`}
          />
        ) : (
          <div className="mb-3 flex flex-col gap-2">
            {known.length === 0 && (
              <p className="text-body-md text-on-surface-variant flex items-center gap-2">
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-success text-base"
                >
                  check_circle
                </span>
                No inputs needed
              </p>
            )}
            {known.map((input) => (
              <FieldRow
                key={input.path}
                input={input}
                value={values[input.path] ?? ''}
                onChange={(value) =>
                  setValues((current) => ({ ...current, [input.path]: value }))
                }
              />
            ))}
          </div>
        )}
        <InlineError message={parseError ?? error} />
        <div className="mt-4 flex items-center justify-between gap-2">
          <div className="flex items-center gap-3">
            {inputs !== null && (
              <button type="button" className={GHOST} onClick={toggleRaw}>
                {raw ? 'Form' : '{} JSON'}
              </button>
            )}
            {!raw && unset > 0 && (
              <span className="text-body-sm text-warning bg-warning/10 flex items-center gap-1.5 rounded-full px-2.5 py-1">
                <span
                  aria-hidden="true"
                  className="border-warning size-2.5 rounded-full border-2"
                />
                {unset} not set
              </span>
            )}
          </div>
          <div className="flex gap-2">
            <button type="button" className={GHOST} onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className={PRIMARY} disabled={pending}>
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                play_arrow
              </span>
              Run
              <kbd
                aria-hidden="true"
                className="text-label-sm bg-primary-foreground/20 rounded px-1 font-sans"
              >
                {MOD}↵
              </kbd>
            </button>
          </div>
        </div>
      </form>
    </Modal>
  );
}

/** Run with an optional trigger payload; one idempotency key per dialog, so a retry is one run. */
export function RunButton({
  sessionId,
  workflowId,
  onStarted,
  size,
  className,
  label = 'Run workflow',
  iconOnly = false,
  disabled = false,
}: {
  sessionId: string;
  workflowId: string;
  onStarted: (executionId: string) => void;
  size?: 'sm';
  /** Replaces the default outline button style, e.g. a segment of a grouped control. */
  className?: string;
  /** The button's accessible name, e.g. "Run check" in a table of workflows. */
  label?: string;
  /** Icon only: the label moves to the tooltip. */
  iconOnly?: boolean;
  disabled?: boolean;
}) {
  const { start } = useExecutionControl(sessionId, workflowId);
  // undefined: dialog closed; null: opened, but the definition could not be read
  const [inputs, setInputs] = useState<StartInput[] | null | undefined>(
    undefined
  );
  const runKey = useRef('');
  const queryClient = useQueryClient();
  const begin = (payload: Record<string, unknown>) =>
    start.mutate(
      { payload, idempotencyKey: runKey.current },
      {
        onSuccess: (result) => {
          setInputs(undefined);
          onStarted(result.executionId);
        },
      }
    );
  const click = async () => {
    runKey.current = newIdempotencyKey();
    try {
      const definition = await queryClient.fetchQuery({
        queryKey: [
          'sessions',
          sessionId,
          'workflows',
          workflowId,
          'definition',
        ],
        queryFn: () => getWorkflowDefinition(sessionId, workflowId),
        staleTime: 30_000,
      });
      setInputs(startInputs(definition.nodes, definition.edges));
    } catch {
      setInputs(null);
    }
  };
  return (
    <>
      <RequireControl
        perm="startRuns"
        command="start"
        target={{ session: sessionId, workflow: workflowId }}
      >
        <Tip label={iconOnly ? label : undefined} disabled={disabled}>
          <button
            type="button"
            disabled={disabled}
            aria-label={label}
            className={
              className ??
              (iconOnly
                ? iconButton('success')
                : buttonVariants({ variant: 'outline', size }))
            }
            onClick={() => void click()}
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-base"
            >
              play_arrow
            </span>
            {!iconOnly && 'Run'}
          </button>
        </Tip>
      </RequireControl>
      {inputs !== undefined && (
        <RunDialog
          inputs={inputs}
          pending={start.isPending}
          error={start.error ? controlErrorMessage(start.error) : null}
          onClose={() => setInputs(undefined)}
          onSubmit={begin}
        />
      )}
    </>
  );
}
