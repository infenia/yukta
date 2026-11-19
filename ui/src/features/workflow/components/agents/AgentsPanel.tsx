// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { lazy, Suspense, useState } from 'react';
import type { Conversation } from '@/types/conversations';
import { cn } from '@/lib/utils';
import { Tip } from '@/components/ui/tooltip';
import { stateWords } from '../../lib/agentLabels';
import { capabilityLook, modeLook, stateLook } from '../../lib/agentLook';
import type { AgentItem } from '../../lib/agentItems';
import { IconTile } from './IconTile';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { AgentComposer } from './AgentComposer';
import { AgentList } from './AgentList';
import { AgentTimeline } from './AgentTimeline';

// the terminal view and xterm come in a chunk of their own, when a terminal agent is shown
const AgentTerminal = lazy(() =>
  import('./AgentTerminal').then((module) => ({
    default: module.AgentTerminal,
  }))
);

/** What this agent is and what a person can do with it, in plain words (the header's info button). */
function AgentDetails({ conversation }: { conversation: Conversation }) {
  const look = stateLook(conversation);
  const mode = modeLook(conversation.protocol);
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <IconTile
          icon={look.icon}
          iconClassName={cn('text-lg', look.live && 'animate-spin')}
          className={cn('size-8 rounded-lg', look.tile)}
        />
        <div className="min-w-0">
          <p className="text-label-md truncate font-semibold">
            {conversation.nodeId}
          </p>
          <p className="text-body-sm text-muted-foreground">
            {conversation.harness} · {stateWords(conversation)}
          </p>
        </div>
      </div>
      <div>
        <h4 className="text-overline text-on-surface-variant mb-1">
          How you talk to it
        </h4>
        <p className="text-body-sm">
          <span className="font-medium">{mode.label}.</span> {mode.hint}.
        </p>
      </div>
      <div>
        <h4 className="text-overline text-on-surface-variant mb-1">
          What you can do
        </h4>
        {conversation.capabilities.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">
            Nothing: this agent only reports what it does.
          </p>
        ) : (
          <ul className="flex flex-col gap-1.5">
            {conversation.capabilities.map((capability) => {
              const info = capabilityLook(capability);
              return (
                <li key={capability} className="flex items-center gap-2">
                  <IconTile
                    icon={info.icon}
                    iconClassName="text-base"
                    className="bg-muted text-on-surface-variant size-7 rounded-lg"
                  />
                  <span className="text-body-sm">
                    <span className="font-medium">{info.label}.</span>{' '}
                    {info.hint}.
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}

/** The title row of the timeline: which agent it shows and what state it is in, or that it shows all. */
function Heading({
  chosen,
  count,
}: {
  chosen: Conversation | undefined;
  count: number;
}) {
  const look = chosen ? stateLook(chosen) : null;
  return (
    <div className="border-border flex flex-wrap items-center gap-3 border-b px-4 py-2">
      <Tip label={chosen ? stateWords(chosen) : ''}>
        <span className="flex">
          <IconTile
            icon={look ? look.icon : 'auto_awesome'}
            iconClassName={cn('text-xl', look?.live && 'animate-spin')}
            className={cn(
              'size-9 rounded-xl',
              look ? look.tile : 'bg-muted text-muted-foreground'
            )}
          />
        </span>
      </Tip>
      <div className="min-w-0">
        <h3 className="text-label-md truncate font-semibold">
          {chosen ? chosen.nodeId : 'All agents of this run'}
        </h3>
        <p className="text-body-sm text-muted-foreground">
          {chosen && look ? (
            <>
              {chosen.harness} · <span>{modeLook(chosen.protocol).label}</span>
              {/* the icon says the rest; only a call for the person is spelled out */}
              <span
                className={cn(
                  look.needsYou ? cn('font-medium', look.text) : 'sr-only'
                )}
              >
                {' · '}
                {stateWords(chosen)}
              </span>
            </>
          ) : (
            `${count} ${count === 1 ? 'agent' : 'agents'}, child runs included`
          )}
        </p>
      </div>
      {chosen && look && (
        <Popover>
          <PopoverTrigger
            aria-label="About this agent"
            className="text-on-surface-variant hover:bg-muted hover:text-on-surface transition-standard ml-auto flex size-8 shrink-0 items-center justify-center rounded-full"
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-xl"
            >
              info
            </span>
          </PopoverTrigger>
          <PopoverContent align="end" className="w-80">
            <AgentDetails conversation={chosen} />
          </PopoverContent>
        </Popover>
      )}
      {!chosen && (
        <p className="text-body-sm text-muted-foreground ml-auto">
          Pick an agent to message it
        </p>
      )}
    </div>
  );
}

/** The Agents tab of the dock: the agents of the run beside one timeline, and a box to talk to one. */
export function AgentsPanel({
  conversations,
  items,
  focus = null,
}: {
  conversations: Conversation[];
  items: AgentItem[];
  /** Selects this conversation; a new nonce selects it again. */
  focus?: { id: string; nonce: number } | null;
}) {
  const [selected, setSelected] = useState<string | null>(null);
  const [seenFocus, setSeenFocus] = useState(focus?.nonce ?? 0);
  // Render-phase: a click on its node on the canvas selects the conversation once
  if (focus && focus.nonce !== seenFocus) {
    setSeenFocus(focus.nonce);
    setSelected(focus.id);
  }
  if (conversations.length === 0) {
    return (
      <div className="text-body-sm text-on-surface-variant flex min-h-0 flex-1 flex-col items-center justify-center gap-2 px-4 py-6 text-center">
        <span
          aria-hidden="true"
          className="material-symbols-outlined bg-surface-variant rounded-xl p-3 text-2xl"
        >
          terminal
        </span>
        <h3 className="text-label-md text-on-surface font-semibold">
          No agents in this run
        </h3>
        <p className="max-w-lg">
          Nodes of type{' '}
          <code className="bg-surface-variant rounded px-1.5">
            AGENT_SESSION
          </code>{' '}
          show up here while they work: their messages, tool calls and requests,
          and a box to talk to them. This run has none yet.
        </p>
        <p className="max-w-lg">
          If you expected one, check that{' '}
          <code className="bg-surface-variant rounded px-1.5">
            yukta.agents.enabled
          </code>{' '}
          is true on the server.
        </p>
      </div>
    );
  }
  // a conversation that is not in the list any more cannot stay selected
  const active = conversations.some((c) => c.id === selected) ? selected : null;
  const chosen = conversations.find((c) => c.id === active);
  return (
    <div className="flex min-h-0 flex-1 flex-col md:flex-row">
      <AgentList
        conversations={conversations}
        selected={active}
        onSelect={setSelected}
      />
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <Heading chosen={chosen} count={conversations.length} />
        {chosen?.protocol === 'PTY' ? (
          <Suspense
            fallback={
              <p className="text-body-sm text-muted-foreground px-4 py-3">
                Opening the terminal…
              </p>
            }
          >
            <AgentTerminal
              key={chosen.id}
              conversation={chosen}
              canType={chosen.capabilities.includes('terminal')}
            />
          </Suspense>
        ) : (
          <>
            <AgentTimeline
              items={items}
              conversations={conversations}
              selected={active}
            />
            <AgentComposer key={chosen?.id ?? 'none'} conversation={chosen} />
          </>
        )}
      </div>
    </div>
  );
}
