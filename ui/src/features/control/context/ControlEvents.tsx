// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQueryClient } from '@tanstack/react-query';
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import { appendBounded, type ActivityEvent, type Entry } from '../lib/activity';
import { ControlEventsContext, FLUSH_MS } from './ControlEventsContext';

/**
 * One connection to the global event stream for the Control page. Events are collected and added
 * to the list a few times a second (so 50 events/s do not mean 50 renders/s); a ControlOutcome
 * refreshes the panels that show control commands. Reconnects do not ask for history: the feed is
 * live only.
 */
export function ControlEventsProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const pending = useRef<Entry[]>([]);
  const seq = useRef(0);
  const [state, setState] = useState<{ events: Entry[]; dropped: number }>({
    events: [],
    dropped: 0,
  });

  const onEvent = useCallback(
    (event: ActivityEvent) => {
      pending.current.push({ ...event, seq: seq.current++ });
      if (
        event.type === 'ControlOutcome' ||
        event.type === 'WorkflowStatusChanged'
      ) {
        // slots are taken and freed as runs start and end; limits change by a control command
        void queryClient.invalidateQueries({
          queryKey: ['control', 'scheduler'],
        });
      }
      if (event.type === 'ControlOutcome') {
        void queryClient.invalidateQueries({ queryKey: ['control', 'audit'] });
        void queryClient.invalidateQueries({
          queryKey: ['control', 'supervisors'],
        });
      }
    },
    [queryClient]
  );
  const { status } = useEventSourceJson<ActivityEvent>(
    '/api/events/stream',
    onEvent
  );

  useEffect(() => {
    const timer = setInterval(() => {
      if (pending.current.length === 0) return;
      const added = pending.current;
      pending.current = [];
      setState((previous) => {
        const next = appendBounded(previous.events, added);
        return {
          events: next.buffer,
          dropped: previous.dropped + next.dropped,
        };
      });
    }, FLUSH_MS);
    return () => clearInterval(timer);
  }, []);

  const value = useMemo(
    () => ({ connected: status === 'open', ...state }),
    [status, state]
  );
  return (
    <ControlEventsContext.Provider value={value}>
      {children}
    </ControlEventsContext.Provider>
  );
}
