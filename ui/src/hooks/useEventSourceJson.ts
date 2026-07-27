// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState } from 'react';

export type EventSourceStatus = 'idle' | 'connecting' | 'open' | 'error';

/**
 * Subscribe to a JSON server-sent-event stream.
 *
 * Passing a null url keeps the hook idle; changing the url closes the previous stream and
 * opens a new one. Errors close the stream permanently (no auto-reconnect) so callers get
 * deterministic behavior — consumers refetch snapshots via TanStack Query instead.
 */
export function useEventSourceJson<T>(
  url: string | null,
  onMessage: (data: T) => void
): { status: EventSourceStatus } {
  // Connection events are recorded against the url they belong to; the visible status is
  // derived so switching urls resets to 'connecting'/'idle' without setState in effects.
  const [connection, setConnection] = useState<{ url: string; status: EventSourceStatus }>({
    url: '',
    status: 'connecting',
  });
  const handlerRef = useRef(onMessage);

  useEffect(() => {
    handlerRef.current = onMessage;
  }, [onMessage]);

  useEffect(() => {
    if (!url) {
      return undefined;
    }
    const source = new EventSource(url);
    source.onopen = () => setConnection({ url, status: 'open' });
    source.onmessage = (event: MessageEvent) => {
      try {
        handlerRef.current(JSON.parse(event.data as string) as T);
      } catch {
        // Malformed frames are dropped; the stream itself stays healthy.
      }
    };
    source.onerror = () => {
      source.close();
      setConnection({ url, status: 'error' });
    };
    return () => {
      source.close();
    };
  }, [url]);

  let status: EventSourceStatus;
  if (!url) {
    status = 'idle';
  } else if (connection.url === url) {
    status = connection.status;
  } else {
    status = 'connecting';
  }

  return { status };
}
