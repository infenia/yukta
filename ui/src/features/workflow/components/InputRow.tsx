// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { InlineError } from '@/components/common/InlineError';
import { CodeBlock } from '@/components/common/CodeBlock';
import { Button } from '@/components/ui/button';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { useMe } from '@/lib/identity';
import {
  escalateInput,
  OPEN_STATES,
  type InputRequest,
  type InputRisk,
} from '../api/inputs';
import {
  Confirm,
  Fold,
  InputAnswerForm,
  inputErrorMessage,
} from './InputAnswerForm';
import { StatusPill } from './StatusPill';

export { inputErrorMessage };

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

/**
 * How the request came here, as a sentence: "Asked guard (asked for approval), then lead
 * (silent), now you." A request that has ended has no "now".
 */
export function ApprovalChain({
  request,
  you,
}: {
  request: InputRequest;
  you: boolean;
}) {
  const live = OPEN_STATES.includes(request.state);
  const steps = request.asked ?? [];
  if (steps.length === 0) return null;
  return (
    <p
      aria-label="Approval chain"
      className="text-body-sm text-on-surface-variant"
    >
      Asked{' '}
      {steps.map((step, i) => (
        <span key={i}>
          {i > 0 && ', then '}
          <b className="text-on-surface">{step.who}</b> (
          {step.said.replace('-', ' ')}
          {step.reason ? `: ${step.reason}` : ''})
        </span>
      ))}
      {live && (
        <>
          , now{' '}
          {you ? (
            <b className="text-on-surface">you</b>
          ) : (
            <>
              tier <b className="text-on-surface">{request.tier}</b>
            </>
          )}
        </>
      )}
      .
    </p>
  );
}

/** One open request: the question, its controls and, for an operator, Answer and Escalate. */
export function InputRow({ request }: { request: InputRequest }) {
  const queryClient = useQueryClient();
  const me = useMe();
  const canAct = !me || me.permissions.controlRuns;
  const [escalating, setEscalating] = useState(false);
  const [reason, setReason] = useState('');
  const [advice, setAdvice] = useState('');
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['inputs'] });
  const escalate = useMutation({
    mutationFn: () =>
      escalateInput(request.executionId, request.id, {
        reason,
        ...(advice && { advice }),
      }),
    onSettled: refresh,
  });
  const busy = escalate.isPending;
  const error = escalate.error;

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
      <ApprovalChain request={request} you={canAct} />
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
          <InputAnswerForm request={request} />
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
