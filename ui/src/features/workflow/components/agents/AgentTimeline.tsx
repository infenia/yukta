// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { cn } from '@/lib/utils';
import type { Conversation } from '@/types/conversations';
import type { AgentItem } from '../../lib/agentItems';
import { clock } from '../../lib/agentLabels';
import { OpenRequest } from './OpenRequest';

const ROW_ESTIMATE = 28;
const FOLLOW_SLACK = 48;

const STATUS_TONE: Record<string, string> = {
  completed: 'text-success',
  done: 'text-success',
  running: 'text-primary',
  pending: 'text-primary',
  failed: 'text-destructive',
};

const caption = 'text-body-sm text-muted-foreground mb-0.5';

/** What an item says. Everything an agent wrote is shown as text, never as markup. */
function ItemBody({
  item,
  conversation,
  everyone,
}: {
  item: AgentItem;
  conversation: Conversation | undefined;
  /** The timeline shows all agents: an open request is a summary, answered in the agent's own view. */
  everyone: boolean;
}) {
  const when = clock(item.at);
  switch (item.kind) {
    case 'message': {
      if (item.role === 'user') {
        const prompt = item.author === 'prompt';
        const who = prompt
          ? 'Prompt from the workflow'
          : `you${item.author ? ` (${item.author})` : ''}`;
        return (
          <div className="flex flex-col items-end">
            <p className={caption}>
              {item.queued && (
                <span className="text-primary font-semibold">Queued · </span>
              )}
              {who}
              {when && `, ${when}`}
            </p>
            <p
              className={cn(
                'max-w-[85%] rounded-2xl px-3 py-2 break-words whitespace-pre-wrap',
                item.queued
                  ? 'border-primary/50 border border-dashed'
                  : 'bg-accent text-accent-foreground'
              )}
            >
              {item.text}
              {item.queued && <span className="sr-only"> (queued)</span>}
            </p>
          </div>
        );
      }
      if (item.role === 'thought') {
        return (
          <p className="text-muted-foreground px-1 break-words whitespace-pre-wrap italic">
            <span className="sr-only">thinking: </span>
            {item.text}
          </p>
        );
      }
      return (
        <div>
          <p className={caption}>
            {conversation?.harness ?? 'agent'}
            {when && ` · ${when}`}
          </p>
          <p className="bg-surface-variant max-w-[85%] rounded-2xl px-3 py-2 break-words whitespace-pre-wrap">
            {item.text}
          </p>
        </div>
      );
    }
    case 'tool':
      return (
        <details className="border-border bg-surface rounded-lg border px-3 py-1.5">
          <summary className="flex cursor-pointer items-baseline gap-2">
            <span className="text-primary text-code-sm font-semibold">
              {item.title}
            </span>
            <span
              className={cn('text-code-sm ml-auto', STATUS_TONE[item.status])}
            >
              {item.status}
            </span>
          </summary>
          {item.input && (
            <pre
              className="text-code-sm mt-1 overflow-x-auto whitespace-pre-wrap"
              aria-label="Input"
            >
              {item.input}
            </pre>
          )}
          {item.output && (
            <pre
              className="text-code-sm mt-1 overflow-x-auto whitespace-pre-wrap"
              aria-label="Output"
            >
              {item.output}
            </pre>
          )}
          {item.truncated && (
            <p className="text-body-sm text-muted-foreground">Shortened.</p>
          )}
        </details>
      );
    case 'plan':
      return (
        <ul className="list-inside list-disc">
          {item.entries.map((entry, i) => (
            <li key={i}>
              {entry.text}{' '}
              <span className="text-muted-foreground">[{entry.status}]</span>
            </li>
          ))}
        </ul>
      );
    case 'request':
      if (
        item.state === 'open' &&
        item.requestId &&
        !everyone &&
        conversation
      ) {
        return (
          <OpenRequest
            executionId={conversation.executionId}
            requestId={item.requestId}
            prompt={item.prompt}
          />
        );
      }
      if (item.state === 'open') {
        return (
          <div className="border-warning/60 bg-warning/10 rounded-lg border px-3 py-2">
            <p className="font-semibold">Waiting for you</p>
            <p className="break-words whitespace-pre-wrap">
              {item.prompt} Open the agent to answer.
            </p>
          </div>
        );
      }
      return (
        <p className="break-words whitespace-pre-wrap">
          <span className="font-medium">Request ({item.state})</span>:{' '}
          {item.prompt}
          {item.decision && (
            <span className="text-muted-foreground">
              {' '}
              — {item.decision}
              {item.by ? ` by ${item.by}` : ''}
            </span>
          )}
        </p>
      );
    case 'result':
      return (
        <p className="break-words whitespace-pre-wrap">
          <span className="font-medium">Result: {item.status}</span>
          {item.reason ? ` — ${item.reason}` : ''}
        </p>
      );
    case 'notice':
      return (
        <p className="text-on-surface-variant break-words whitespace-pre-wrap">
          {item.level}: {item.text}
        </p>
      );
  }
}

/**
 * The items of the agents in one list, oldest first: a virtual list the screen reader announces as
 * it grows. With every agent in it, each item carries a chip with its node.
 */
export function AgentTimeline({
  items,
  conversations,
  selected,
}: {
  items: AgentItem[];
  conversations: Conversation[];
  /** One conversation id, or null for all. */
  selected: string | null;
}) {
  const shown = selected
    ? items.filter((item) => item.conversationId === selected)
    : items;
  const node = (id: string) =>
    conversations.find((c) => c.id === id)?.nodeId ?? id;
  const bodyRef = useRef<HTMLDivElement>(null);
  const [follow, setFollow] = useState(true);
  const virtualizer = useVirtualizer({
    count: shown.length,
    getScrollElement: () => bodyRef.current,
    estimateSize: () => ROW_ESTIMATE,
    measureElement: (el) => el.getBoundingClientRect().height || ROW_ESTIMATE,
    overscan: 8,
    initialRect: { width: 0, height: 224 },
  });

  // new items scroll into view while the person has not scrolled up
  useEffect(() => {
    if (follow && shown.length > 0) {
      virtualizer.scrollToIndex(shown.length - 1, { align: 'end' });
    }
  }, [shown.length, follow, virtualizer]);

  return (
    <div
      ref={bodyRef}
      role="log"
      aria-live="polite"
      aria-label="Agent timeline"
      tabIndex={0}
      onScroll={(event) => {
        const el = event.currentTarget;
        setFollow(
          el.scrollHeight - el.scrollTop - el.clientHeight < FOLLOW_SLACK
        );
      }}
      className="text-body-sm min-h-0 flex-1 overflow-y-auto px-4 py-2"
    >
      {shown.length === 0 ? (
        <p className="text-muted-foreground">Nothing yet.</p>
      ) : (
        <div
          className="relative"
          style={{ height: virtualizer.getTotalSize() }}
        >
          {virtualizer.getVirtualItems().map((row) => {
            const item = shown[row.index];
            return (
              <div
                key={item.key}
                ref={virtualizer.measureElement}
                data-index={row.index}
                className={cn('absolute left-0 flex w-full gap-2 py-0.5')}
                style={{ top: row.start }}
              >
                {selected === null && (
                  <span className="bg-accent text-label-sm text-accent-foreground h-fit shrink-0 rounded-full px-2.5 py-0.5">
                    {node(item.conversationId)}
                  </span>
                )}
                <div className="min-w-0 flex-1">
                  <ItemBody
                    item={item}
                    conversation={conversations.find(
                      (c) => c.id === item.conversationId
                    )}
                    everyone={selected === null}
                  />
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
