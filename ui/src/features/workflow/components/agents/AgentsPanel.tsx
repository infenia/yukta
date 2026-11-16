// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import type { Conversation } from '@/types/conversations';
import type { AgentItem } from '../../lib/agentItems';
import { AgentList } from './AgentList';
import { AgentTimeline } from './AgentTimeline';

/** The Agents tab of the dock: the agents of the run beside one timeline. Read only. */
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
      <div className="text-body-sm text-muted-foreground flex min-h-0 flex-1 items-center justify-center px-4 py-6">
        No agent has run in this workflow yet. Add an AGENT_SESSION node to see
        its conversation here.
      </div>
    );
  }
  // a conversation that is not in the list any more cannot stay selected
  const active = conversations.some((c) => c.id === selected) ? selected : null;
  return (
    <div className="flex min-h-0 flex-1">
      <AgentList
        conversations={conversations}
        selected={active}
        onSelect={setSelected}
      />
      <AgentTimeline
        items={items}
        conversations={conversations}
        selected={active}
      />
    </div>
  );
}
