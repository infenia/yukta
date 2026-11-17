// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useEffect, useRef, useState } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import { api } from '@/lib/apiClient';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import type { Conversation, ConversationEvent } from '@/types/conversations';
import { resizeTerminal, sendTerminalInput } from '../../api/conversations';

/** Keys typed within this time go out in one request. */
export const KEY_BATCH_MS = 30;

const EVENT_NAMES = ['event'] as const;

/** The bytes of a base64 text; none for text that is not base64. */
function decode(data: string): Uint8Array {
  try {
    return Uint8Array.from(atob(data), (char) => char.charCodeAt(0));
  } catch {
    return new Uint8Array();
  }
}

/**
 * The terminal of a `pty` agent: its output from the stream, the screen that arrives on connect
 * (and after a gap) as a fresh start, the keys of the person posted in batches, and the size of
 * the view posted when it changes. It is a view only when the agent has ended or the person may
 * not steer it. No clipboard or link handler is loaded: the program can write anything.
 */
export function AgentTerminal({
  conversation,
  canType,
}: {
  conversation: Conversation;
  /** False when the person may not steer this agent. */
  canType: boolean;
}) {
  const host = useRef<HTMLDivElement>(null);
  const term = useRef<Terminal | null>(null);
  const [size, setSize] = useState({ cols: 80, rows: 24 });
  const [refused, setRefused] = useState(false);
  const typing = canType && !refused && conversation.state !== 'ENDED';
  const typingRef = useRef(typing);
  const id = conversation.id;

  useEffect(() => {
    // the element is there: effects run after the commit that rendered it
    const element = host.current as HTMLDivElement;
    const terminal = new Terminal({
      convertEol: false,
      cursorBlink: true,
      disableStdin: !typingRef.current,
      fontSize: 13,
      scrollback: 2000,
    });
    const fit = new FitAddon();
    terminal.loadAddon(fit);
    terminal.open(element);
    term.current = terminal;

    const fail = () => setRefused(true);
    let pending: number[] = [];
    let timer: ReturnType<typeof setTimeout> | undefined;
    const flush = () => {
      timer = undefined;
      const bytes = Uint8Array.from(pending);
      pending = [];
      sendTerminalInput(id, bytes).catch(fail);
    };
    const keys = terminal.onData((data) => {
      if (!typingRef.current) return;
      pending.push(...new TextEncoder().encode(data));
      timer ??= setTimeout(flush, KEY_BATCH_MS);
    });
    const resized = terminal.onResize(({ cols, rows }) => {
      setSize({ cols, rows });
      if (typingRef.current) {
        resizeTerminal(id, cols, rows).catch(fail);
      }
    });
    const observer = new ResizeObserver(() => fit.fit());
    observer.observe(element);
    fit.fit();

    return () => {
      clearTimeout(timer);
      observer.disconnect();
      keys.dispose();
      resized.dispose();
      terminal.dispose();
      term.current = null;
    };
  }, [id]);

  useEffect(() => {
    typingRef.current = typing;
    (term.current as Terminal).options.disableStdin = !typing;
  }, [typing]);

  const onEvent = useCallback((event: ConversationEvent) => {
    const terminal = term.current;
    if (!terminal || event.kind !== 'TERMINAL') return;
    const { data } = event;
    if (typeof data.screen === 'string') {
      // a settled screen: start the view again from it, in its colours when the host sent them
      const shown = typeof data.styled === 'string' ? data.styled : data.screen;
      terminal.reset();
      terminal.write(shown.replace(/\n/g, '\r\n'));
      const row = Number(data.cursorRow ?? 0) + 1;
      const col = Number(data.cursorCol ?? 0) + 1;
      terminal.write(`\x1b[${row};${col}H`);
    } else if (typeof data.data === 'string') {
      terminal.write(decode(data.data));
    }
  }, []);

  const url = api`/api/executions/${conversation.executionId}/conversations/stream?tree=true&terminal=true&kinds=TERMINAL&conversationId=${id}`;
  useEventSourceJson<ConversationEvent>(
    url,
    onEvent,
    conversation.state !== 'ENDED',
    undefined,
    EVENT_NAMES
  );

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2 px-4 py-3">
      <p className="border-warning/50 bg-warning/10 text-body-sm rounded-lg border px-3 py-2">
        Terminal agent: what you type goes straight to the program and is not
        reviewed. Only operators allowed to steer this node can type.
      </p>
      <div
        ref={host}
        role="region"
        aria-label={`Terminal of ${conversation.nodeId}`}
        className="bg-terminal min-h-40 flex-1 overflow-hidden rounded-xl p-3"
      />
      <p className="text-body-sm text-muted-foreground">
        {size.cols} columns × {size.rows} rows.{' '}
        {typing
          ? 'Typing goes to the terminal when it has focus (click it).'
          : conversation.state === 'ENDED'
            ? 'The agent has ended; this is its last screen.'
            : 'You may not type into this terminal.'}
      </p>
    </div>
  );
}
