// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState } from 'react';
import { CodeBlock } from '@/components/common/CodeBlock';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { cliEquivalent } from '../lib/equivalent';
import type { Call, Msg } from '../types';

type Result = Extract<Msg, { role: 'tool' }>;

const pretty = (value: unknown): string => JSON.stringify(value, null, 2);

/** The text of a result: JSON from a tool is shown indented. */
function resultText(text: string): string {
  try {
    return pretty(JSON.parse(text));
  } catch {
    return text;
  }
}

/** One tool call: what it is, whether it ran, and (when it may change something) the approval. */
export function ToolCard({
  call,
  result,
  awaiting,
  onApprove,
}: {
  call: Call;
  result?: Result;
  /** The person has to approve this call before it runs. */
  awaiting: boolean;
  onApprove: (ok: boolean) => void;
}) {
  const [open, setOpen] = useState(false);
  // only a call seen running can say how long it took; one restored with its result cannot
  const began = useRef<number | null>(null);
  const [took, setTook] = useState<number | null>(null);
  const finished = result !== undefined;
  useEffect(() => {
    if (!finished && began.current === null) began.current = Date.now();
  }, [finished]);
  useEffect(() => {
    if (finished && began.current !== null) setTook(Date.now() - began.current);
  }, [finished]);
  const status = result
    ? result.isError
      ? { icon: 'error', tone: 'text-destructive', text: 'Failed' }
      : { icon: 'check_circle', tone: 'text-success', text: 'Done' }
    : awaiting
      ? { icon: 'build', tone: 'text-attention', text: 'Needs approval' }
      : { icon: 'progress_activity', tone: 'text-attention', text: 'Running' };
  const expanded = open || awaiting;
  const cli = cliEquivalent(call);
  return (
    <div className="border-border bg-surface-container-low my-2 overflow-hidden rounded-lg border">
      <button
        type="button"
        aria-expanded={expanded}
        onClick={() => setOpen(!open)}
        className="transition-standard hover:bg-surface-container flex w-full items-center gap-2 px-3 py-2 text-left"
      >
        <span
          aria-hidden="true"
          className={cn(
            'material-symbols-outlined text-base',
            status.tone,
            status.icon === 'progress_activity' && 'animate-spin'
          )}
        >
          {status.icon}
        </span>
        <span className="text-code-sm text-on-surface min-w-0 flex-1 truncate">
          {call.name}
        </span>
        <span className="text-label-sm text-on-surface-variant">
          {status.text}
          {took !== null && ` · ${(took / 1000).toFixed(1)} s`}
        </span>
      </button>
      {expanded && (
        <div className="border-border flex flex-col gap-2 border-t p-2">
          <CodeBlock code={pretty(call.args)} label="arguments" grow />
          {cli && <CodeBlock code={cli} label="same in the CLI" grow />}
          {result && (
            <CodeBlock code={resultText(result.result)} label="result" grow />
          )}
        </div>
      )}
      {awaiting && (
        <div className="border-border flex justify-end gap-2 border-t p-2">
          <Button variant="outline" size="sm" onClick={() => onApprove(false)}>
            Deny
          </Button>
          <Button size="sm" onClick={() => onApprove(true)}>
            Approve
          </Button>
        </div>
      )}
    </div>
  );
}
