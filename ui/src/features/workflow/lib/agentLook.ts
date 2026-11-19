// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Conversation } from '@/types/conversations';

/** How an agent's state looks: an icon, a tint for its tile, and a text colour for its words. */
export interface StateLook {
  icon: string;
  tile: string;
  text: string;
  /** A live state pulses, and one that needs the person is called out. */
  live?: boolean;
  needsYou?: boolean;
}

const TONE = {
  muted: {
    tile: 'bg-muted text-muted-foreground',
    text: 'text-muted-foreground',
  },
  primary: { tile: 'bg-primary/10 text-primary', text: 'text-primary' },
  attention: {
    tile: 'bg-attention/15 text-attention',
    text: 'text-attention',
  },
  success: { tile: 'bg-success/10 text-success', text: 'text-success' },
  destructive: {
    tile: 'bg-destructive/10 text-destructive',
    text: 'text-destructive',
  },
} as const;

export function stateLook(
  c: Pick<Conversation, 'state' | 'outcome'>
): StateLook {
  switch (c.state) {
    case 'STARTING':
      return { icon: 'hourglass_empty', ...TONE.muted };
    case 'RUNNING':
      return { icon: 'progress_activity', ...TONE.primary, live: true };
    case 'WAITING':
      return { icon: 'pending', ...TONE.attention, needsYou: true };
    case 'IDLE':
      return { icon: 'pause', ...TONE.muted };
    case 'ENDED':
      return c.outcome === 'failure'
        ? { icon: 'error', ...TONE.destructive }
        : c.outcome === 'incomplete'
          ? { icon: 'warning', ...TONE.attention }
          : { icon: 'check_circle', ...TONE.success };
  }
}

/** What each capability an agent offers lets a person do, in plain words. */
const CAPABILITY: Record<
  string,
  { icon: string; label: string; hint: string }
> = {
  message: {
    icon: 'send',
    label: 'Messages',
    hint: 'You can send it messages',
  },
  terminal: {
    icon: 'keyboard',
    label: 'Terminal',
    hint: 'You can type into its terminal',
  },
  interrupt: {
    icon: 'stop_circle',
    label: 'Interrupt',
    hint: 'You can stop what it is doing',
  },
  steer: {
    icon: 'edit',
    label: 'Steer',
    hint: 'You can redirect it while it works',
  },
};

export const capabilityLook = (capability: string) =>
  CAPABILITY[capability] ?? {
    icon: 'smart_toy',
    label: capability,
    hint: capability,
  };

/** The way a person talks to the agent, instead of the protocol's name. */
export const modeLook = (protocol: Conversation['protocol']) =>
  protocol === 'PTY'
    ? { label: 'Terminal', hint: 'An interactive terminal session (PTY)' }
    : {
        label: 'Chat',
        hint: `A message conversation (${protocol === 'ACP' ? 'ACP' : 'JSONL'} protocol)`,
      };
