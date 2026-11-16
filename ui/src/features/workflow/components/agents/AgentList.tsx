// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { cn } from '@/lib/utils';
import type { Conversation, ConversationState } from '@/types/conversations';

const DOT: Record<ConversationState, string> = {
  STARTING: 'bg-muted-foreground',
  RUNNING: 'bg-attention live-dot',
  WAITING: 'bg-warning',
  IDLE: 'bg-primary',
  ENDED: 'bg-outline-variant',
};

/** The agents of the run: "All" and one row for each conversation. */
export function AgentList({
  conversations,
  selected,
  onSelect,
}: {
  conversations: Conversation[];
  /** The conversation id the timeline shows, or null for all of them. */
  selected: string | null;
  onSelect: (id: string | null) => void;
}) {
  const row = (active: boolean) =>
    cn(
      'flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-left text-label-md',
      active
        ? 'bg-accent text-accent-foreground'
        : 'text-on-surface-variant hover:bg-surface-variant'
    );
  return (
    <ul
      aria-label="Agents"
      className="flex w-52 shrink-0 flex-col gap-0.5 overflow-y-auto p-2"
    >
      <li>
        <button
          type="button"
          aria-pressed={selected === null}
          onClick={() => onSelect(null)}
          className={row(selected === null)}
        >
          All
          <span className="text-body-sm text-muted-foreground ml-auto tabular-nums">
            {conversations.length}
          </span>
        </button>
      </li>
      {conversations.map((c) => (
        <li key={c.id}>
          <button
            type="button"
            aria-pressed={selected === c.id}
            onClick={() => onSelect(c.id)}
            className={row(selected === c.id)}
          >
            <span
              aria-hidden="true"
              className={cn('size-2 shrink-0 rounded-full', DOT[c.state])}
            />
            <span className="min-w-0 flex-1">
              <span className="block truncate">{c.nodeId}</span>
              <span className="text-body-sm text-muted-foreground block truncate">
                {c.harness}
                {c.model ? ` · ${c.model}` : ''}
              </span>
            </span>
            {c.queued > 0 && (
              <span className="text-body-sm text-muted-foreground shrink-0">
                {c.queued} queued
              </span>
            )}
            <span className="sr-only">{c.state.toLowerCase()}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}
