// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createContext, useContext } from 'react';
import type { Entry } from '../lib/activity';

/** How often events that arrived since the last tick are added to the list. */
export const FLUSH_MS = 250;

/** Polling interval of the control panels while the stream is down. */
export const FALLBACK_POLL_MS = 60_000;

export interface ControlEvents {
  connected: boolean;
  events: Entry[];
  dropped: number;
}

export const ControlEventsContext = createContext<ControlEvents>({
  connected: false,
  events: [],
  dropped: 0,
});

export const useControlEvents = () => useContext(ControlEventsContext);

/** How often a panel that follows control events should poll: never while the stream is up. */
export function useFallbackPoll(): number | false {
  return useControlEvents().connected ? false : FALLBACK_POLL_MS;
}
