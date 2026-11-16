// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import { CodeBlock } from '@/components/common/CodeBlock';
import { Button } from '@/components/ui/button';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { ApiError } from '@/lib/apiClient';
import { answerInput, type AnswerBody, type InputRequest } from '../api/inputs';
import { controlErrorMessage } from '../hooks/useExecutionControl';

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

export function Fold({ title, code }: { title: string; code: string }) {
  return (
    <details>
      <summary className="text-label-md text-on-surface-variant cursor-pointer">
        {title}
      </summary>
      <CodeBlock code={code} label={title.toLowerCase()} />
    </details>
  );
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

export function Confirm({
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

/**
 * The part of a request that answers it: the buttons of an approval or a choice, or the text area
 * of a text or structured answer. A HIGH risk request asks for a confirmation first, and the
 * version the caller saw is sent, so an answer to a request that changed meanwhile is refused.
 * Used by the request row, the inbox popover and the toasts.
 */
export function InputAnswerForm({
  request,
  onAnswered,
}: {
  request: InputRequest;
  /** Called once the server accepted the answer. */
  onAnswered?: () => void;
}) {
  const queryClient = useQueryClient();
  const [pending, setPending] = useState<AnswerBody | null>(null);
  const answer = useMutation({
    mutationFn: (body: AnswerBody) =>
      answerInput(request.executionId, request.id, {
        ...body,
        expectedVersion: request.version,
      }),
    onSuccess: () => onAnswered?.(),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['inputs'] }),
  });
  const submit = (body: AnswerBody) =>
    request.risk === 'HIGH' ? setPending(body) : answer.mutate(body);
  return (
    <>
      <Controls request={request} busy={answer.isPending} submit={submit} />
      <InlineError
        message={answer.error ? inputErrorMessage(answer.error) : null}
      />
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
    </>
  );
}
