// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { InlineError } from '@/components/common/InlineError';
import { Callout } from '@/components/common/Callout';
import { CodeBlock } from '@/components/common/CodeBlock';
import { Button } from '@/components/ui/button';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { Tip } from '@/components/ui/tooltip';
import { REQUIRED_ROLE, useMe } from '@/lib/identity';
import { cn } from '@/lib/utils';
import {
  escalateInput,
  OPEN_STATES,
  type InputRequest,
  type InputRisk,
} from '../api/inputs';
import { deadlineShare, tierLabel } from '../lib/inputNodes';
import { formatDateTime, formatRelative } from '../lib/timeFormat';
import { Confirm, InputAnswerForm, inputErrorMessage } from './InputAnswerForm';
import { StatusPill } from './StatusPill';

export { inputErrorMessage };

const RISKS: Record<
  InputRisk,
  { label: string; icon: string; classes: string; hint: string }
> = {
  LOW: {
    label: 'Low risk',
    icon: 'verified_user',
    classes: 'text-success border-success/30 bg-success/10',
    hint: 'A supervisor or a person may answer.',
  },
  MEDIUM: {
    label: 'Medium risk',
    icon: 'warning',
    classes: 'text-warning border-warning/30 bg-warning/10',
    hint: 'Check the context before you answer.',
  },
  HIGH: {
    label: 'High risk · needs a person',
    icon: 'gpp_maybe',
    classes: 'text-destructive border-destructive/30 bg-destructive/10',
    hint: 'Only a person can answer, and you are asked to confirm.',
  },
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

/** The bar's colour: calm above half the time, a warning below, urgent in the last fifth. */
const barTone = (share: number) =>
  share > 0.5 ? 'bg-primary' : share > 0.2 ? 'bg-warning' : 'bg-destructive';

function Countdown({ request }: { request: InputRequest }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const deadline = request.deadline as string;
  const share = deadlineShare(request, now);
  const overdue = Date.parse(deadline) <= now;
  return (
    <div className="flex min-w-32 flex-col gap-1">
      <span className="text-label-sm text-on-surface-variant flex items-center gap-1">
        <span aria-hidden className="material-symbols-outlined">
          timer
        </span>
        {remaining(deadline, now)}
        <span className="sr-only">
          {overdue
            ? 'Overdue, the timeout action applies now'
            : `Answer by ${formatDateTime(deadline)}`}
        </span>
      </span>
      <div className="bg-muted h-1 overflow-hidden rounded-full">
        <div
          role="progressbar"
          aria-label="Time left to answer"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={Math.round(share * 100)}
          style={{ width: `${share * 100}%` }}
          className={cn('transition-standard h-full', barTone(share))}
        />
      </div>
    </div>
  );
}

function RiskBadge({ risk }: { risk: InputRisk }) {
  const { label, icon, classes, hint } = RISKS[risk];
  return (
    <Tip label={hint}>
      <span
        className={cn(
          'text-label-sm inline-flex items-center gap-1 rounded-full border px-2',
          classes
        )}
      >
        <span aria-hidden className="material-symbols-outlined">
          {icon}
        </span>
        {label}
      </span>
    </Tip>
  );
}

/** The facts the asker attached, as a readable list; only text is ever rendered, never HTML. */
function ContextList({ context }: { context: Record<string, unknown> }) {
  const entries = Object.entries(context);
  return (
    <details>
      <summary className="text-label-md text-on-surface-variant cursor-pointer">
        Context ({entries.length})
      </summary>
      <dl className="mt-1 grid grid-cols-[max-content_1fr] gap-x-3 gap-y-1">
        {entries.map(([key, value]) => (
          <div key={key} className="contents">
            <dt className="text-label-sm text-on-surface-variant font-mono">
              {key}
            </dt>
            <dd className="text-body-sm min-w-0 wrap-break-word">
              {typeof value === 'object' && value !== null ? (
                <CodeBlock code={JSON.stringify(value, null, 2)} label={key} />
              ) : (
                String(value)
              )}
            </dd>
          </div>
        ))}
      </dl>
    </details>
  );
}

/** Text only, never HTML; a long text folds away behind its first lines and has a copy button. */
function PlainText({ label, text }: { label: string; text: string }) {
  if (text.length <= LONG) {
    return <p className="text-headline-md whitespace-pre-wrap">{text}</p>;
  }
  return (
    <details>
      <summary className="text-headline-md cursor-pointer">
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

/** One open request: the question, who is asked and why, and for an operator, Answer and Escalate. */
export function InputRow({
  request,
  onNode,
}: {
  request: InputRequest;
  /** Makes the asking node a link that opens it on the canvas. */
  onNode?: (nodeId: string) => void;
}) {
  const queryClient = useQueryClient();
  const me = useMe();
  const canAct = !me || me.permissions.controlRuns;
  const inline = request.kind === 'APPROVAL' || request.kind === 'CHOICE';
  const [escalating, setEscalating] = useState(false);
  const [reason, setReason] = useState('');
  const [advice, setAdvice] = useState('');
  const escalateButton = useRef<HTMLButtonElement>(null);
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
  const closeEscalation = () => {
    setEscalating(false);
    // the dialog takes focus away; give it back to the button that opened it
    queueMicrotask(() => escalateButton.current?.focus());
  };

  return (
    <li
      className={cn(
        'border-border bg-card shadow-card @container grid gap-3 rounded-xl border border-l-4 p-3',
        request.state === 'ESCALATED'
          ? 'border-l-warning'
          : 'border-l-attention'
      )}
    >
      <div className="flex max-w-3xl min-w-0 flex-col gap-2.5">
        <div className="flex flex-wrap items-center gap-2">
          <StatusPill status={request.state} testId="input-state" />
          <RiskBadge risk={request.risk} />
          {request.deadline && <Countdown request={request} />}
        </div>
        <div id={`prompt-${request.id}`}>
          <PlainText label="prompt" text={request.prompt} />
        </div>
        <p className="text-body-sm text-on-surface-variant">
          Asked by{' '}
          {onNode ? (
            <button
              type="button"
              className="text-primary font-mono hover:underline"
              onClick={() => onNode(request.nodeId)}
            >
              {request.nodeId}
            </button>
          ) : (
            <span className="font-mono">{request.nodeId}</span>
          )}{' '}
          · waiting since {formatRelative(request.createdAt)} · for:{' '}
          {canAct ? 'you' : tierLabel(request.tier)}
        </p>
        <ApprovalChain request={request} you={canAct} />
        {request.advice && (
          <Callout
            kind="info"
            compact
            title="Advice from the previous approver"
          >
            {request.advice}
          </Callout>
        )}
        {canAct ? (
          <div
            role="group"
            aria-labelledby={`prompt-${request.id}`}
            className={cn(
              'flex gap-x-5 gap-y-2',
              inline ? 'flex-wrap items-center' : 'flex-col items-start'
            )}
          >
            <div className={inline ? undefined : 'w-full max-w-xl'}>
              <InputAnswerForm request={request} size="lg" />
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button
                ref={escalateButton}
                size="sm"
                variant="ghost"
                disabled={busy}
                onClick={() => setEscalating(true)}
              >
                <span aria-hidden className="material-symbols-outlined">
                  move_up
                </span>
                Escalate
              </Button>
              <span className="text-label-sm text-on-surface-variant">
                Not your call? Pass it to the next approver.
              </span>
            </div>
          </div>
        ) : (
          <p className="text-body-sm text-on-surface-variant">
            You can see this request. Answering needs the{' '}
            {REQUIRED_ROLE.controlRuns} role.
          </p>
        )}
        {Object.keys(request.context).length > 0 && (
          <ContextList context={request.context} />
        )}
      </div>
      <InlineError message={error ? inputErrorMessage(error) : null} />
      {escalating && (
        <Confirm
          title="Escalate this request?"
          disabled={reason.trim() === ''}
          onClose={closeEscalation}
          onConfirm={() => {
            escalate.mutate();
            closeEscalation();
          }}
        >
          <p className="text-body-md text-on-surface-variant mb-3">
            It moves to the next approver in this request's route, with your
            reason. If there is no one above the current tier (
            {tierLabel(request.tier)}), it stays here, flagged as escalated, for
            whoever can still answer.
          </p>
          <label htmlFor={`reason-${request.id}`} className="text-label-md">
            Reason
          </label>
          <p className="text-label-sm text-on-surface-variant">
            Why you are not deciding (the next approver sees this).
          </p>
          <textarea
            id={`reason-${request.id}`}
            rows={2}
            autoFocus
            className={textareaFieldClassName}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
          <label
            htmlFor={`advice-${request.id}`}
            className="text-label-md mt-2 block"
          >
            Advice for the next approver (optional)
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
