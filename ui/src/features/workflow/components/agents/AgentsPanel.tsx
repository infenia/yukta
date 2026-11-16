// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { lazy, Suspense, useState } from 'react';
import type { Conversation } from '@/types/conversations';
import { stateWords } from '../../lib/agentLabels';
import type { AgentItem } from '../../lib/agentItems';
import { AgentComposer } from './AgentComposer';
import { AgentList } from './AgentList';
import { AgentTimeline } from './AgentTimeline';

// the terminal view and xterm come in a chunk of their own, when a terminal agent is shown
const AgentTerminal = lazy(() =>
  import('./AgentTerminal').then((module) => ({
    default: module.AgentTerminal,
  }))
);

const chip =
  'bg-surface-variant text-label-sm text-on-surface-variant rounded-full px-2.5 py-0.5';

/** The title row of the timeline: which agent it shows, or that it shows all. */
function Heading({ chosen }: { chosen: Conversation | undefined }) {
  return (
    <div className="border-border flex flex-wrap items-center gap-2 border-b px-4 py-2">
      {chosen ? (
        <>
          <h3 className="text-label-md font-semibold">{chosen.nodeId}</h3>
          <span className={chip}>{chosen.harness}</span>
          <span className={chip}>{chosen.protocol.toLowerCase()}</span>
          <span className="bg-accent text-label-sm text-accent-foreground rounded-full px-2.5 py-0.5">
            {stateWords(chosen)}
          </span>
          <span className="ml-auto flex flex-wrap gap-1.5">
            {chosen.capabilities.map((capability) => (
              <span key={capability} className={chip}>
                {capability}
              </span>
            ))}
          </span>
        </>
      ) : (
        <>
          <h3 className="text-label-md font-semibold">
            All agents of this run
          </h3>
          <span className={chip}>includes child runs</span>
          <span className={`${chip} ml-auto`}>
            pick an agent to send a message
          </span>
        </>
      )}
    </div>
  );
}

/** The Agents tab of the dock: the agents of the run beside one timeline, and a box to talk to one. */
export function AgentsPanel({
  conversations,
  items,
}: {
  conversations: Conversation[];
  items: AgentItem[];
}) {
  const [selected, setSelected] = useState<string | null>(null);
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
        <Heading chosen={chosen} />
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
