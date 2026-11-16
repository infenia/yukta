// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Conversation } from '@/types/conversations';

/** What a person calls the state of an agent. */
export function stateWords(c: Conversation): string {
  switch (c.state) {
    case 'STARTING':
      return 'Starting';
    case 'RUNNING':
      return 'Working';
    case 'WAITING':
      return 'Waiting for you';
    case 'IDLE':
      return 'Idle';
    case 'ENDED':
      return c.outcome === 'success'
        ? 'Ended, succeeded'
        : c.outcome === 'failure'
          ? 'Ended, failed'
          : c.outcome === 'incomplete'
            ? 'Ended, incomplete'
            : 'Ended';
  }
}

/** The time of day of an ISO instant, as hh:mm. */
export function clock(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? ''
    : d.toLocaleTimeString([], {
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      });
}
