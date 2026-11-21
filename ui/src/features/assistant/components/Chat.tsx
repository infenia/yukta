// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { IconButton } from '@/components/common/IconButton';
import { InlineError } from '@/components/common/InlineError';
import { Markdown } from '@/components/common/Markdown';
import { Button } from '@/components/ui/button';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { Tip } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { useAssistant } from '../hooks/useAssistant';
import { BUSY_ASSISTANT_EVENT } from '../lib/open';
import type { PageContext } from '../lib/pageContext';
import type { ChatModel, Msg } from '../types';
import { ToolCard } from './ToolCard';

/** The pages a `search_docs` result named, as title and link. */
function sources(result: string | undefined): { title: string; url: string }[] {
  return [
    ...(result ?? '').matchAll(/^## (.+?)(?: — .*)?\n(https?:\S+)$/gm),
  ].map(([, title, url]) => ({ title, url }));
}

/** Messages drawn at once; earlier ones stay behind "Show earlier" so a long thread stays quick. */
const WINDOW = 60;

const WHERE = {
  device: 'Runs on your device; nothing leaves this browser.',
  cloud: 'Your messages go to the model provider through this Yukta server.',
};

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
  const { messages, busy, error, pending, send, retry, stop, approve } =
    useAssistant(model, context);
  const [draft, setDraft] = useState('');
  const [shown, setShown] = useState(WINDOW);
  const input = useRef<HTMLTextAreaElement>(null);
  const scroller = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open) (input.current as HTMLTextAreaElement).focus();
  }, [open]);
  // the launcher shows a ring while the assistant works, also with the card closed
  useEffect(() => {
    window.dispatchEvent(
      new CustomEvent(BUSY_ASSISTANT_EVENT, { detail: busy })
    );
  }, [busy]);
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
  const usage = model.usage?.() ?? null;

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
            <p className="text-label-sm text-on-surface-variant">
              {model.onDevice ? WHERE.device : WHERE.cloud}
            </p>
            <div className="flex flex-wrap gap-2">
              {context.suggestions.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  onClick={() => submit(suggestion)}
                  className="border-border text-label-md text-on-surface transition-standard hover:bg-surface-container-high rounded-full border px-3 py-1.5"
                >
                  {suggestion}
                </button>
              ))}
            </div>
          </div>
        ) : (
          messages.map((m: Msg, index) =>
            index < messages.length - shown ? (
              index === 0 ? (
                <Button
                  key="earlier"
                  variant="ghost"
                  size="sm"
                  onClick={() => setShown(shown + WINDOW)}
                >
                  Show earlier
                </Button>
              ) : null
            ) : m.role === 'user' ? (
              <div key={index} className="my-3 flex justify-end">
                <p className="bg-surface-container-high text-body-md text-on-surface max-w-[85%] rounded-2xl px-3 py-2 wrap-break-word whitespace-pre-wrap">
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
                <div className="flex flex-wrap gap-2">
                  {m.calls
                    .flatMap((c) => sources(results.get(c.id)?.result))
                    .map(({ title, url }) => (
                      <a
                        key={url}
                        href={url}
                        target="_blank"
                        rel="noreferrer"
                        className="border-border text-label-sm text-primary transition-standard hover:bg-surface-container-high rounded-full border px-2.5 py-1"
                      >
                        {title}
                      </a>
                    ))}
                </div>
                {!busy && index === last && m.text && (
                  <div className="mt-1 flex gap-1">
                    <IconButton
                      icon="content_copy"
                      label="Copy answer"
                      onClick={() =>
                        void navigator.clipboard?.writeText(m.text)
                      }
                    />
                    <IconButton
                      icon="refresh"
                      label="Retry"
                      onClick={() => void retry()}
                    />
                  </div>
                )}
              </div>
            ) : null
          )
        )}
        <InlineError message={error} />
      </div>
      <div className="border-border flex flex-col gap-2 border-t p-3">
        {(context.label || usage) && (
          <div className="text-label-sm text-on-surface-variant flex justify-between gap-2">
            <p>{context.label && `Looking at ${context.label}`}</p>
            {usage && (
              <p
                role="meter"
                aria-label="Context used"
                aria-valuemin={0}
                aria-valuemax={usage.total}
                aria-valuenow={usage.used}
              >
                Context {Math.round((usage.used / usage.total) * 100)}%
              </p>
            )}
          </div>
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
