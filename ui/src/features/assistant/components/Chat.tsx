// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { InlineError } from '@/components/common/InlineError';
import { Markdown } from '@/components/common/Markdown';
import { Button } from '@/components/ui/button';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { Tip } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { useAssistant } from '../hooks/useAssistant';
import type { PageContext } from '../lib/pageContext';
import type { ChatModel, Msg } from '../types';
import { ToolCard } from './ToolCard';

/** The conversation and its composer. Stays mounted while the panel is closed. */
export function Chat({
  model,
  context,
  open,
}: {
  model: ChatModel;
  context: PageContext;
  open: boolean;
}) {
  const { messages, busy, error, pending, send, stop, approve } = useAssistant(
    model,
    context
  );
  const [draft, setDraft] = useState('');
  const input = useRef<HTMLTextAreaElement>(null);
  const scroller = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open) (input.current as HTMLTextAreaElement).focus();
  }, [open]);
  useEffect(() => {
    const el = scroller.current as HTMLDivElement;
    el.scrollTop = el.scrollHeight;
  }, [messages, pending]);

  const submit = (text: string) => {
    const clean = text.trim();
    if (!clean || busy) return;
    setDraft('');
    void send(clean);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      submit(draft);
    }
  };

  const results = new Map(
    messages.flatMap((m) => (m.role === 'tool' ? [[m.id, m] as const] : []))
  );
  const last = messages.length - 1;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div
        ref={scroller}
        aria-live="polite"
        className="min-h-0 flex-1 overflow-y-auto px-4 py-3"
      >
        {messages.length === 0 ? (
          <div className="flex h-full flex-col justify-end gap-3 pb-2">
            <p className="text-body-md text-on-surface-variant">
              Ask about your sessions, workflows and runs, or have me start and
              control them.
            </p>
            <div className="flex flex-wrap gap-2">
              {context.suggestions.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  onClick={() => submit(suggestion)}
                  className="border-border text-label-md text-on-surface hover:bg-surface-container-high transition-standard rounded-full border px-3 py-1.5"
                >
                  {suggestion}
                </button>
              ))}
            </div>
          </div>
        ) : (
          messages.map((m: Msg, index) =>
            m.role === 'user' ? (
              <div key={index} className="my-3 flex justify-end">
                <p className="bg-surface-container-high text-body-md text-on-surface max-w-[85%] rounded-2xl px-3 py-2 break-words whitespace-pre-wrap">
                  {m.text}
                </p>
              </div>
            ) : m.role === 'assistant' ? (
              <div key={index} className="text-body-md text-on-surface my-3">
                {m.text && <Markdown source={m.text} />}
                {m.calls.map((call) => (
                  <ToolCard
                    key={call.id}
                    call={call}
                    result={results.get(call.id)}
                    awaiting={pending?.call.id === call.id}
                    onApprove={approve}
                  />
                ))}
                {busy && index === last && (
                  <span
                    aria-hidden="true"
                    className="bg-on-surface-variant inline-block h-4 w-1.5 animate-pulse rounded-sm align-middle"
                  />
                )}
              </div>
            ) : null
          )
        )}
        <InlineError message={error} />
      </div>
      <div className="border-border flex flex-col gap-2 border-t p-3">
        {context.label && (
          <p className="text-label-sm text-on-surface-variant">
            Looking at {context.label}
          </p>
        )}
        <div className="flex items-end gap-2">
          <textarea
            ref={input}
            value={draft}
            rows={Math.min(5, draft.split('\n').length)}
            aria-label="Message"
            placeholder="Ask Yukta"
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={onKeyDown}
            className={cn(textareaFieldClassName, 'min-h-9 flex-1 resize-none')}
          />
          {busy ? (
            <Tip label="Stop">
              <Button
                size="icon-lg"
                variant="outline"
                aria-label="Stop"
                onClick={stop}
              >
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-base"
                >
                  stop
                </span>
              </Button>
            </Tip>
          ) : (
            <Button
              size="icon-lg"
              aria-label="Send"
              disabled={!draft.trim()}
              onClick={() => submit(draft)}
            >
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                send
              </span>
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
