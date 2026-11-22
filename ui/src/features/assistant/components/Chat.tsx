// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { IconButton } from '@/components/common/IconButton';
import { InlineError } from '@/components/common/InlineError';
import { Markdown } from '@/components/common/Markdown';
import { Button } from '@/components/ui/button';
import { Bubble, BubbleContent } from '@/components/ui/bubble';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupTextarea,
} from '@/components/ui/input-group';
import {
  Message,
  MessageContent,
  MessageFooter,
} from '@/components/ui/message';
import {
  MessageScroller,
  MessageScrollerButton,
  MessageScrollerContent,
  MessageScrollerItem,
  MessageScrollerProvider,
  MessageScrollerViewport,
} from '@/components/ui/message-scroller';
import {
  TraceCollapsible,
  TraceCollapsibleContent,
  TraceCollapsibleTrigger,
  TraceIcon,
  TraceLine,
  TraceTitle,
} from '@/components/ui/trace';
import { Tip } from '@/components/ui/tooltip';
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

  useEffect(() => {
    if (open) (input.current as HTMLTextAreaElement).focus();
  }, [open]);
  // the launcher shows a ring while the assistant works, also with the card closed
  useEffect(() => {
    window.dispatchEvent(
      new CustomEvent(BUSY_ASSISTANT_EVENT, { detail: busy })
    );
  }, [busy]);

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
      {messages.length === 0 ? (
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">
          <div className="flex h-full flex-col justify-end gap-3 pb-2">
            <span
              aria-hidden="true"
              className="assistant-gradient flex size-9 items-center justify-center rounded-xl"
            >
              <span className="material-symbols-outlined text-lg">
                auto_awesome
              </span>
            </span>
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
                  className="border-primary/30 bg-primary/5 text-label-md text-on-surface transition-standard hover:bg-primary/15 rounded-full border px-3 py-1.5"
                >
                  {suggestion}
                </button>
              ))}
            </div>
          </div>
          <InlineError message={error} />
        </div>
      ) : (
        <MessageScrollerProvider autoScroll defaultScrollPosition="end">
          <MessageScroller>
            <MessageScrollerViewport
              aria-label="Conversation"
              className="px-4 py-3"
            >
              <MessageScrollerContent className="gap-3">
                {messages.length > shown && (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setShown(shown + WINDOW)}
                  >
                    Show earlier
                  </Button>
                )}
                {messages.map((m: Msg, index) =>
                  index < messages.length - shown ? null : m.role === 'user' ? (
                    <MessageScrollerItem key={index} scrollAnchor>
                      <Message align="end">
                        <MessageContent>
                          <Bubble align="end">
                            <BubbleContent
                              render={<p />}
                              className="assistant-gradient text-body-md rounded-2xl rounded-br-md px-3 py-2 whitespace-pre-wrap"
                            >
                              {m.text}
                            </BubbleContent>
                          </Bubble>
                        </MessageContent>
                      </Message>
                    </MessageScrollerItem>
                  ) : m.role === 'assistant' ? (
                    <MessageScrollerItem key={index}>
                      <Message>
                        <MessageContent className="text-body-md text-on-surface">
                          <Bubble variant="ghost">
                            <BubbleContent className="text-body-md">
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
                              <Sources
                                found={m.calls.flatMap((c) =>
                                  sources(results.get(c.id)?.result)
                                )}
                              />
                            </BubbleContent>
                          </Bubble>
                          {!busy && index === last && m.text && (
                            <MessageFooter className="gap-1">
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
                            </MessageFooter>
                          )}
                        </MessageContent>
                      </Message>
                    </MessageScrollerItem>
                  ) : null
                )}
                <InlineError message={error} />
              </MessageScrollerContent>
            </MessageScrollerViewport>
            <MessageScrollerButton />
          </MessageScroller>
        </MessageScrollerProvider>
      )}
      <div className="border-primary/15 bg-card/50 flex flex-col gap-2 border-t p-3 backdrop-blur-sm">
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
        <InputGroup>
          <InputGroupTextarea
            ref={input}
            value={draft}
            rows={Math.min(5, draft.split('\n').length)}
            aria-label="Message"
            placeholder="Ask Yukta"
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={onKeyDown}
            className="min-h-9"
          />
          <InputGroupAddon align="inline-end" className="self-end pb-1.5">
            {busy ? (
              <Tip label="Stop">
                <Button
                  size="icon"
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
                size="icon"
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
          </InputGroupAddon>
        </InputGroup>
      </div>
    </div>
  );
}

/** The docs pages a reply drew on, behind one "Used N sources" line. */
function Sources({ found }: { found: { title: string; url: string }[] }) {
  const unique = [...new Map(found.map((f) => [f.url, f])).values()];
  if (unique.length === 0) return null;
  return (
    <TraceCollapsible className="mt-2">
      <TraceCollapsibleTrigger>
        <TraceLine>
          <TraceIcon>
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-base"
            >
              menu_book
            </span>
          </TraceIcon>
          <TraceTitle className="text-label-md">
            Used {unique.length} {unique.length === 1 ? 'source' : 'sources'}
          </TraceTitle>
        </TraceLine>
      </TraceCollapsibleTrigger>
      <TraceCollapsibleContent className="flex flex-wrap gap-2">
        {unique.map(({ title, url }) => (
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
      </TraceCollapsibleContent>
    </TraceCollapsible>
  );
}
