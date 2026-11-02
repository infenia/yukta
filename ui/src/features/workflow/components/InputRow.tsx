// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import { CodeBlock } from '@/components/common/CodeBlock';
import { Button } from '@/components/ui/button';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { ApiError } from '@/lib/apiClient';
import { useMe } from '@/lib/identity';
import {
  answerInput,
  escalateInput,
  type AnswerBody,
  type InputRequest,
  type InputRisk,
} from '../api/inputs';
import { controlErrorMessage } from '../hooks/useExecutionControl';
import { StatusPill } from './StatusPill';

const RISK_CLASSES: Record<InputRisk, string> = {
  LOW: 'text-success border-success/30 bg-success/10',
  MEDIUM: 'text-warning border-warning/30 bg-warning/10',
  HIGH: 'text-destructive border-destructive/30 bg-destructive/10',
};

const LONG = 200;

/** Time left until the deadline; it ticks every second unless the user prefers reduced motion. */
export function remaining(deadline: string, now: number): string {
  const seconds = Math.ceil((Date.parse(deadline) - now) / 1000);
  if (seconds <= 0) return 'overdue';
  if (seconds < 60) return `${seconds} s left`;
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)} min ${seconds % 60} s left`;
  }
  return `${Math.floor(seconds / 3600)} h ${Math.floor((seconds % 3600) / 60)} min left`;
}

function Countdown({ deadline }: { deadline: string }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return (
    <span className="text-label-sm text-on-surface-variant">
      {remaining(deadline, now)}
    </span>
  );
}

/** Text only, never HTML; a long text folds away behind its first lines and has a copy button. */
function PlainText({ label, text }: { label: string; text: string }) {
  if (text.length <= LONG) {
    return <p className="text-body-md whitespace-pre-wrap">{text}</p>;
  }
  return (
    <details>
      <summary className="text-body-md cursor-pointer">
        {text.slice(0, LONG)}… <span className="text-primary">show all</span>
      </summary>
      <CodeBlock code={text} label={label} />
    </details>
  );
}

function Fold({ title, code }: { title: string; code: string }) {
  return (
    <details>
      <summary className="text-label-md text-on-surface-variant cursor-pointer">
        {title}
      </summary>
      <CodeBlock code={code} label={title.toLowerCase()} />
    </details>
  );
}

/** The sentence under a failed answer or escalation. */
export function inputErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const rule = error.fieldErrors?.find((e) => e.field === 'rule');
    if (error.status === 403 && rule) return `Not allowed: ${rule.message}`;
    if (error.status === 409) {
      return 'Already answered, expired or changed. The list was refreshed.';
    }
  }
  return controlErrorMessage(error);
}

function Controls({
  request,
  busy,
  submit,
}: {
  request: InputRequest;
  busy: boolean;
  submit: (body: AnswerBody) => void;
}) {
  const [text, setText] = useState('');
  const [invalid, setInvalid] = useState<string | null>(null);
  if (request.kind === 'APPROVAL' || request.kind === 'CHOICE') {
    const options =
      request.options.length > 0
        ? request.options
        : [
            { id: 'approve', label: 'Approve' },
            { id: 'reject', label: 'Reject' },
          ];
    return (
      <div className="flex flex-wrap gap-2">
        {options.map((option) => (
          <Button
            key={option.id}
            size="sm"
            variant={option.id === 'reject' ? 'outline' : 'default'}
            disabled={busy}
            onClick={() => submit({ decision: option.id })}
          >
            {option.label}
          </Button>
        ))}
      </div>
    );
  }
  const structured = request.kind === 'STRUCTURED';
  const send = () => {
    if (!structured) return submit({ decision: 'answer', text });
    try {
      const data: unknown = JSON.parse(text);
      if (typeof data !== 'object' || data === null || Array.isArray(data)) {
        throw new Error('not an object');
      }
      setInvalid(null);
      submit({ decision: 'answer', data: data as Record<string, unknown> });
    } catch {
      setInvalid('The answer must be a JSON object.');
    }
  };
  return (
    <div className="flex flex-col gap-2">
      {structured && request.responseSchema && (
        <Fold title="Schema" code={request.responseSchema} />
      )}
      <textarea
        aria-label={structured ? 'Answer as JSON' : 'Answer'}
        rows={3}
        className={textareaFieldClassName}
        value={text}
        onChange={(event) => setText(event.target.value)}
      />
      <InlineError message={invalid} />
      <div>
        <Button size="sm" disabled={busy || text.trim() === ''} onClick={send}>
          Send answer
        </Button>
      </div>
    </div>
  );
}

function Confirm({
  title,
  onClose,
  onConfirm,
  children,
  disabled = false,
}: {
  title: string;
  onClose: () => void;
  onConfirm: () => void;
  children?: ReactNode;
  disabled?: boolean;
}) {
  return (
    <Modal title={title} onClose={onClose}>
      {children}
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>
          Cancel
        </Button>
        <Button disabled={disabled} onClick={onConfirm}>
          Confirm
        </Button>
      </div>
    </Modal>
  );
}

/** One open request: the question, its controls and, for an operator, Answer and Escalate. */
export function InputRow({ request }: { request: InputRequest }) {
  const queryClient = useQueryClient();
  const me = useMe();
  const canAct = !me || me.permissions.controlRuns;
  const [pending, setPending] = useState<AnswerBody | null>(null);
  const [escalating, setEscalating] = useState(false);
  const [reason, setReason] = useState('');
  const [advice, setAdvice] = useState('');
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['inputs'] });
  const answer = useMutation({
    mutationFn: (body: AnswerBody) =>
      answerInput(request.executionId, request.id, {
        ...body,
        expectedVersion: request.version,
      }),
    onSettled: refresh,
  });
  const escalate = useMutation({
    mutationFn: () =>
      escalateInput(request.executionId, request.id, {
        reason,
        ...(advice && { advice }),
      }),
    onSettled: refresh,
  });
  const submit = (body: AnswerBody) =>
    request.risk === 'HIGH' ? setPending(body) : answer.mutate(body);
  const busy = answer.isPending || escalate.isPending;
  const error = answer.error ?? escalate.error;

  return (
    <li className="border-border bg-card flex flex-col gap-2 rounded-xl border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <StatusPill status={request.state} testId="input-state" />
        <span
          className={`text-label-sm rounded-full border px-2 ${RISK_CLASSES[request.risk]}`}
        >
          {request.risk} risk
        </span>
        <span className="text-label-sm text-on-surface-variant">
          <span className="font-mono">{request.nodeId}</span> · tier{' '}
          {request.tier}
        </span>
        {request.deadline && <Countdown deadline={request.deadline} />}
      </div>
      <PlainText label="prompt" text={request.prompt} />
      {Object.keys(request.context).length > 0 && (
        <Fold title="Context" code={JSON.stringify(request.context, null, 2)} />
      )}
      {request.advice && (
        <p className="text-body-sm text-on-surface-variant">
          Advice from supervisor: {request.advice}
        </p>
      )}
      {canAct && (
        <>
          <Controls request={request} busy={busy} submit={submit} />
          <div>
            <Button
              size="sm"
              variant="ghost"
              disabled={busy}
              onClick={() => setEscalating(true)}
            >
              Escalate
            </Button>
          </div>
        </>
      )}
      <InlineError message={error ? inputErrorMessage(error) : null} />
      {pending && (
        <Confirm
          title="Answer a high-risk request?"
          onClose={() => setPending(null)}
          onConfirm={() => {
            answer.mutate(pending);
            setPending(null);
          }}
        >
          <p className="text-body-md">
            This request is rated HIGH risk. Send “{pending.decision}”?
          </p>
        </Confirm>
      )}
      {escalating && (
        <Confirm
          title="Escalate to the next tier?"
          disabled={reason.trim() === ''}
          onClose={() => setEscalating(false)}
          onConfirm={() => {
            escalate.mutate();
            setEscalating(false);
          }}
        >
          <label htmlFor={`reason-${request.id}`} className="text-label-md">
            Reason
          </label>
          <textarea
            id={`reason-${request.id}`}
            rows={2}
            className={textareaFieldClassName}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
          <label htmlFor={`advice-${request.id}`} className="text-label-md">
            Advice (optional)
          </label>
          <textarea
            id={`advice-${request.id}`}
            rows={2}
            className={textareaFieldClassName}
            value={advice}
            onChange={(event) => setAdvice(event.target.value)}
          />
        </Confirm>
      )}
    </li>
  );
}
