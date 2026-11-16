// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { cn } from '@/lib/utils';
import type { Conversation } from '@/types/conversations';
import type { AgentItem } from '../../lib/agentItems';
import { OpenRequest } from './OpenRequest';

const ROW_ESTIMATE = 28;
const FOLLOW_SLACK = 48;

/** What an item says, as text. Everything an agent wrote is shown as text, never as markup. */
function ItemBody({
  item,
  executionId,
}: {
  item: AgentItem;
  executionId: string;
}) {
  switch (item.kind) {
    case 'message':
      return (
        <p className="wrap-break-word whitespace-pre-wrap">
          <span className="text-muted-foreground">
            {item.role === 'user'
              ? `you${item.author && item.author !== 'prompt' ? ` (${item.author})` : ''}: `
              : item.role === 'thought'
                ? 'thinking: '
                : ''}
          </span>
          {item.text}
          {item.queued && (
            <span className="text-body-sm text-muted-foreground">
              {' '}
              (queued)
            </span>
          )}
        </p>
      );
    case 'tool':
      return (
        <details>
          <summary className="cursor-pointer">
            <span className="font-medium">{item.title}</span>{' '}
            <span className="text-muted-foreground">[{item.status}]</span>
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
      if (item.state === 'open' && item.requestId) {
        return (
          <OpenRequest
            executionId={executionId}
            requestId={item.requestId}
            prompt={item.prompt}
          />
        );
      }
      return (
        <p className="wrap-break-word whitespace-pre-wrap">
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
        <p className="wrap-break-word whitespace-pre-wrap">
          <span className="font-medium">Result: {item.status}</span>
          {item.reason ? ` — ${item.reason}` : ''}
        </p>
      );
    case 'notice':
      return (
        <p className="text-on-surface-variant wrap-break-word whitespace-pre-wrap">
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
                  <span className="bg-surface-variant text-label-sm text-on-surface-variant h-fit shrink-0 rounded px-1.5 py-0.5">
                    {node(item.conversationId)}
                  </span>
                )}
                <div className="min-w-0 flex-1">
                  <ItemBody
                    item={item}
                    executionId={
                      conversations.find((c) => c.id === item.conversationId)
                        ?.executionId ?? ''
                    }
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
