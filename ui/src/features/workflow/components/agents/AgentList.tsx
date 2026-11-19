// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { cn } from '@/lib/utils';
import type { Conversation } from '@/types/conversations';
import { stateLook } from '../../lib/agentLook';
import { stateWords } from '../../lib/agentLabels';
import { IconTile } from './IconTile';
import { Tip } from '@/components/ui/tooltip';

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
        ? 'border-border bg-muted text-on-surface'
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
          <IconTile
            icon="auto_awesome"
            className="bg-muted text-muted-foreground size-7 rounded-lg"
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
            <Tip label={stateWords(c)}>
              <span className="flex">
                <IconTile
                  icon={stateLook(c).icon}
                  iconClassName={cn(
                    'text-lg',
                    stateLook(c).live && 'animate-spin'
                  )}
                  className={cn('size-7 rounded-lg', stateLook(c).tile)}
                />
              </span>
            </Tip>
            <span className="min-w-0 flex-1">
              <span className="block truncate font-semibold">{c.nodeId}</span>
              <span className="text-body-sm text-muted-foreground truncate max-md:sr-only md:block">
                {c.harness}
                {/* the icon says the rest; only a call for the person is spelled out */}
                <span
                  className={cn(
                    stateLook(c).needsYou
                      ? cn('font-medium', stateLook(c).text)
                      : 'sr-only'
                  )}
                >
                  {' · '}
                  {stateWords(c)}
                </span>
              </span>
            </span>
            {c.queued > 0 && (
              <Tip
                label={`${c.queued} queued ${c.queued === 1 ? 'message' : 'messages'}`}
              >
                <span className="bg-primary/10 text-primary text-label-sm flex shrink-0 items-center gap-0.5 rounded-full px-1.5 py-0.5 tabular-nums">
                  <span
                    aria-hidden="true"
                    className="material-symbols-outlined text-sm"
                  >
                    schedule
                  </span>
                  <span aria-hidden="true" className="tabular-nums">
                    {c.queued}
                  </span>
                  <span className="sr-only">{c.queued} queued</span>
                </span>
              </Tip>
            )}
          </button>
        </li>
      ))}
    </ul>
  );
}
