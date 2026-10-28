// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState } from 'react';
import { openEventStream, type KeyedEventSource } from '@/lib/keyedEventSource';

export type EventSourceStatus = 'idle' | 'connecting' | 'open' | 'error';

/**
 * Subscribe to a JSON server-sent-event stream.
 *
 * Passing a null url keeps the hook idle; changing the url closes the previous stream and
 * opens a new one. On error the stream is reopened with exponential backoff (1 s doubling to
 * 30 s, reset once a connection opens) while `reconnect` is true; the server replays history,
 * so consumers must tolerate duplicates. With `reconnect` false an error ends the stream.
 * Frames are JSON unless `parse` says otherwise (the raw log stream sends plain text lines).
 */
export const RECONNECT_BASE_MS = 1000;
export const RECONNECT_MAX_MS = 30_000;

export function useEventSourceJson<T>(
  url: string | null,
  onMessage: (data: T) => void,
  reconnect = true,
  parse: (data: string) => T = (data) => JSON.parse(data) as T
): { status: EventSourceStatus } {
  // Connection events are recorded against the url they belong to; the visible status is
  // derived so switching urls resets to 'connecting'/'idle' without setState in effects.
  const [connection, setConnection] = useState<{ url: string; status: EventSourceStatus }>({
    url: '',
    status: 'connecting',
  });
  const handlerRef = useRef(onMessage);
  const reconnectRef = useRef(reconnect);
  const parseRef = useRef(parse);

  useEffect(() => {
    handlerRef.current = onMessage;
    reconnectRef.current = reconnect;
    parseRef.current = parse;
  }, [onMessage, reconnect, parse]);

  useEffect(() => {
    if (!url) {
      return undefined;
    }
    let source: EventSource | KeyedEventSource | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let attempt = 0;

    const connect = () => {
      const current = openEventStream(url);
      source = current;
      current.onopen = () => {
        attempt = 0;
        setConnection({ url, status: 'open' });
      };
      current.onmessage = (event: MessageEvent) => {
        try {
          handlerRef.current(parseRef.current(event.data as string));
        } catch {
          // Malformed frames are dropped; the stream itself stays healthy.
        }
      };
      current.onerror = () => {
        current.close();
        setConnection({ url, status: 'error' });
        if (reconnectRef.current) {
          const delay = Math.min(RECONNECT_BASE_MS * 2 ** attempt, RECONNECT_MAX_MS);
          attempt += 1;
          timer = setTimeout(connect, delay);
        }
      };
    };

    connect();
    return () => {
      clearTimeout(timer);
      source?.close();
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
