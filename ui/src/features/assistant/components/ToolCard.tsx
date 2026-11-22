// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState } from 'react';
import { CodeBlock } from '@/components/common/CodeBlock';
import { Button } from '@/components/ui/button';
import {
  TraceCollapsible,
  TraceCollapsibleContent,
  TraceCollapsibleTrigger,
  TraceDescription,
  TraceIcon,
  TraceLine,
  TraceTitle,
} from '@/components/ui/trace';
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
  const line = result?.isError
    ? 'error'
    : result || awaiting
      ? undefined
      : 'running';
  const expanded = open || awaiting;
  const cli = cliEquivalent(call);
  return (
    <TraceCollapsible open={expanded} onOpenChange={setOpen} className="my-2">
      <TraceCollapsibleTrigger>
        <TraceLine status={line}>
          <TraceIcon>
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
          </TraceIcon>
          <TraceTitle className="text-code-sm">{call.name}</TraceTitle>
          <TraceDescription className="text-label-sm">
            {status.text}
            {took !== null && ` · ${(took / 1000).toFixed(1)} s`}
          </TraceDescription>
        </TraceLine>
      </TraceCollapsibleTrigger>
      <TraceCollapsibleContent className="flex flex-col gap-2">
        <CodeBlock code={pretty(call.args)} label="arguments" grow />
        {cli && <CodeBlock code={cli} label="same in the CLI" grow />}
        {result && (
          <CodeBlock code={resultText(result.result)} label="result" grow />
        )}
        {awaiting && (
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => onApprove(false)}
            >
              Deny
            </Button>
            <Button size="sm" onClick={() => onApprove(true)}>
              Approve
            </Button>
          </div>
        )}
      </TraceCollapsibleContent>
    </TraceCollapsible>
  );
}
