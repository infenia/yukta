// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { cn } from '@/lib/utils';
import type { Conversation, ConversationState } from '@/types/conversations';
import { stateWords } from '../../lib/agentLabels';

const DOT: Record<ConversationState, string> = {
  STARTING: 'bg-muted-foreground',
  RUNNING: 'bg-primary',
  WAITING: 'bg-warning',
  IDLE: 'bg-on-surface-variant',
  ENDED: 'bg-success',
};

/** The agents of the run: "All" and one row for each conversation (chips on a narrow screen). */
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
  const waiting = conversations.filter((c) => c.state === 'WAITING').length;
  const row = (active: boolean) =>
    cn(
      'text-label-md flex shrink-0 items-center gap-2 rounded-full border px-3 py-1.5 text-left md:w-full md:rounded-lg md:border-transparent md:px-2.5',
      active
        ? 'border-primary/40 bg-accent text-accent-foreground'
        : 'border-border text-on-surface-variant hover:bg-surface-variant'
    );
  return (
    <ul
      aria-label="Agents"
      className="border-border flex shrink-0 gap-2 overflow-x-auto border-b p-2 md:w-60 md:flex-col md:gap-0.5 md:overflow-x-visible md:overflow-y-auto md:border-r md:border-b-0"
    >
      <li>
        <button
          type="button"
          aria-pressed={selected === null}
          onClick={() => onSelect(null)}
          className={row(selected === null)}
        >
          <span
            aria-hidden="true"
            className="bg-muted-foreground size-2 shrink-0 rounded-full"
          />
          <span className="min-w-0 flex-1">
            <span className="block font-semibold">All</span>
            <span className="text-body-sm text-muted-foreground max-md:sr-only md:block">
              {conversations.length}{' '}
              {conversations.length === 1 ? 'agent' : 'agents'}
              {waiting > 0 ? `, ${waiting} waiting` : ''}
            </span>
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
              <span className="block truncate font-semibold">{c.nodeId}</span>
              <span className="text-body-sm text-muted-foreground truncate max-md:sr-only md:block">
                {c.harness}
                {c.protocol === 'ACP' ? ' · acp' : ''} · {stateWords(c)}
              </span>
            </span>
            {c.queued > 0 && (
              <span className="text-body-sm text-muted-foreground shrink-0">
                {c.queued} queued
              </span>
            )}
          </button>
        </li>
      ))}
    </ul>
  );
}
