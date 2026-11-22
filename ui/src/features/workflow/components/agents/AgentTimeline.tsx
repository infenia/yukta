// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { Bubble, BubbleContent } from '@/components/ui/bubble';
import { Message, MessageContent } from '@/components/ui/message';
import {
  TraceCollapsible,
  TraceCollapsibleContent,
  TraceCollapsibleTrigger,
  TraceIcon,
  TraceLine,
  TraceTitle,
} from '@/components/ui/trace';
import { cn } from '@/lib/utils';
import type { Conversation } from '@/types/conversations';
import type { AgentItem } from '../../lib/agentItems';
import { clock } from '../../lib/agentLabels';
import { OpenRequest } from './OpenRequest';
import { Tip } from '@/components/ui/tooltip';

const ROW_ESTIMATE = 28;
const FOLLOW_SLACK = 48;

type ToolItem = Extract<AgentItem, { kind: 'tool' }>;

/** A row of the timeline: an item, or the tool calls that came in a row. */
type Row =
  | Exclude<AgentItem, { kind: 'tool' }>
  | { kind: 'tools'; key: string; conversationId: string; tools: ToolItem[] };

const DONE = ['completed', 'done'];

/** Only a failure is marked, in words: a call that went well needs nothing. */
function FailedMark({ status }: { status: string }) {
  return status === 'failed' ? (
    <span className="text-destructive text-label-sm">failed</span>
  ) : (
    <span className="sr-only">{status}</span>
  );
}

/** A call still going, in words. */
function spinner(status: string) {
  return (
    <Tip label={status}>
      <span className="text-label-sm animate-pulse">running</span>
    </Tip>
  );
}

const isRunning = (status: string) =>
  !DONE.includes(status) && status !== 'failed';

/** The status of one call as a trace line: a spinner, a check or a cross. */
function CallLine({
  status,
  children,
}: {
  status: 'failed' | 'running' | 'done';
  children: ReactNode;
}) {
  const icon = {
    failed: { name: 'error', tone: 'text-destructive' },
    running: { name: 'progress_activity', tone: 'text-attention animate-spin' },
    done: { name: 'check_circle', tone: 'text-success' },
  }[status];
  return (
    <TraceLine
      status={
        status === 'failed'
          ? 'error'
          : status === 'running'
            ? 'running'
            : undefined
      }
    >
      <TraceIcon>
        <span
          aria-hidden="true"
          className={cn('material-symbols-outlined text-base', icon.tone)}
        >
          {icon.name}
        </span>
      </TraceIcon>
      {children}
    </TraceLine>
  );
}

/** One tool call: a quiet line with its name; what went in and out shows when opened. */
function ToolCall({ item }: { item: ToolItem }) {
  const running = isRunning(item.status);
  return (
    <TraceCollapsible>
      <TraceCollapsibleTrigger>
        <CallLine
          status={
            item.status === 'failed' ? 'failed' : running ? 'running' : 'done'
          }
        >
          <TraceTitle className="text-body-sm">{item.title}</TraceTitle>
          <FailedMark status={item.status} />
          {running && spinner(item.status)}
        </CallLine>
      </TraceCollapsibleTrigger>
      <TraceCollapsibleContent className="flex flex-col gap-1">
        {item.input && (
          <pre
            className="text-code-sm text-muted-foreground overflow-x-auto whitespace-pre-wrap"
            aria-label="Input"
          >
            {item.input}
          </pre>
        )}
        {item.output && (
          <pre
            className="text-code-sm text-on-surface overflow-x-auto whitespace-pre-wrap"
            aria-label="Output"
          >
            {item.output}
          </pre>
        )}
        {item.truncated && (
          <p className="text-body-sm text-muted-foreground">Shortened.</p>
        )}
      </TraceCollapsibleContent>
    </TraceCollapsible>
  );
}

/** Tool calls in a row, folded into one quiet line: "Used 5 tools · Bash ×2, Grep, Read ×2". */
function ToolGroup({ tools }: { tools: ToolItem[] }) {
  const counts = new Map<string, number>();
  tools.forEach((t) => counts.set(t.title, (counts.get(t.title) ?? 0) + 1));
  const failed = tools.filter((t) => t.status === 'failed').length;
  const running = tools.some((t) => isRunning(t.status));
  const status = failed > 0 ? 'failed' : running ? 'running' : 'done';
  return (
    <TraceCollapsible>
      <TraceCollapsibleTrigger>
        <CallLine status={status}>
          <TraceTitle className="text-body-sm">
            <span className="sr-only">{tools.length} tool calls</span>
            {[...counts]
              .map(([title, n]) => (n > 1 ? `${title} ×${n}` : title))
              .join(', ')}
          </TraceTitle>
          {failed > 0 && (
            <span className="text-destructive text-label-sm shrink-0">
              {failed} failed
            </span>
          )}
          {running && spinner(status)}
        </CallLine>
      </TraceCollapsibleTrigger>
      <TraceCollapsibleContent className="flex flex-col gap-1">
        {tools.map((tool) => (
          <ToolCall key={tool.key} item={tool} />
        ))}
      </TraceCollapsibleContent>
    </TraceCollapsible>
  );
}

/** Text that is long folds to a few lines, with a toggle, so one prompt cannot fill the view. */
function Clamped({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  const long = text.length > 500 || text.split('\n').length > 8;
  return (
    <>
      <span className={cn(long && !open && 'line-clamp-6')}>{text}</span>
      {long && (
        <button
          type="button"
          onClick={() => setOpen((value) => !value)}
          className="text-muted-foreground hover:text-on-surface text-label-sm mt-1 block"
        >
          {open ? 'Show less' : 'Show more'}
        </button>
      )}
    </>
  );
}

/** What an item says. Everything an agent wrote is shown as text, never as markup. */
function ItemBody({
  item,
  conversation,
  everyone,
}: {
  item: Exclude<AgentItem, { kind: 'tool' }>;
  conversation: Conversation | undefined;
  /** The timeline shows all agents: an open request is a summary, answered in the agent's own view. */
  everyone: boolean;
}) {
  const when = clock(item.at);
  switch (item.kind) {
    case 'message': {
      if (item.role === 'user') {
        return (
          <Tip label={when}>
            <Message align="end">
              <MessageContent className="items-end gap-1">
              {item.queued && (
                <span className="text-muted-foreground text-label-sm">
                  Queued
                </span>
              )}
              <Bubble align="end" variant={item.queued ? 'outline' : 'default'}>
                <BubbleContent
                  className={cn(
                    'text-body-md rounded-2xl rounded-br-md px-3 py-2 whitespace-pre-wrap',
                    item.queued ? 'border-dashed' : 'assistant-gradient'
                  )}
                >
                  <Clamped text={item.text} />
                  {item.queued && <span className="sr-only"> (queued)</span>}
                </BubbleContent>
              </Bubble>
              </MessageContent>
            </Message>
          </Tip>
        );
      }
      if (item.role === 'thought') {
        return (
          <p className="text-muted-foreground text-body-sm px-2 break-words whitespace-pre-wrap italic">
            <span className="sr-only">thinking: </span>
            {item.text}
          </p>
        );
      }
      return (
        <Tip label={when}>
          <Message>
            <MessageContent>
              <Bubble variant="ghost">
                <BubbleContent className="text-body-md text-on-surface whitespace-pre-wrap">
                  {item.text}
                </BubbleContent>
              </Bubble>
            </MessageContent>
          </Message>
        </Tip>
      );
    }
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
  const filtered = selected
    ? items.filter((item) => item.conversationId === selected)
    : items;
  // tool calls in a row (of one agent) fold into one line
  const shown = filtered.reduce<Row[]>((rows, item) => {
    const last = rows[rows.length - 1];
    if (item.kind !== 'tool') return [...rows, item];
    if (last?.kind === 'tools' && last.conversationId === item.conversationId) {
      return [...rows.slice(0, -1), { ...last, tools: [...last.tools, item] }];
    }
    return [
      ...rows,
      {
        kind: 'tools',
        key: item.key,
        conversationId: item.conversationId,
        tools: [item],
      },
    ];
  }, []);
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
          className="relative mx-auto w-full max-w-3xl"
          style={{ height: virtualizer.getTotalSize() }}
        >
          {virtualizer.getVirtualItems().map((row) => {
            const item = shown[row.index];
            return (
              <div
                key={item.key}
                ref={virtualizer.measureElement}
                data-index={row.index}
                className="absolute left-0 flex w-full flex-col gap-1 py-1.5"
                style={{ top: row.start }}
              >
                {/* with several agents, the name shows once when the speaker changes */}
                {selected === null &&
                  conversations.length > 1 &&
                  shown[row.index - 1]?.conversationId !==
                    item.conversationId && (
                    <span className="text-label-sm text-muted-foreground px-2">
                      {node(item.conversationId)}
                    </span>
                  )}
                <div className="min-w-0 flex-1">
                  {item.kind === 'tools' ? (
                    item.tools.length === 1 ? (
                      <ToolCall item={item.tools[0]} />
                    ) : (
                      <ToolGroup tools={item.tools} />
                    )
                  ) : (
                    <ItemBody
                      item={item}
                      conversation={conversations.find(
                        (c) => c.id === item.conversationId
                      )}
                      everyone={selected === null}
                    />
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
