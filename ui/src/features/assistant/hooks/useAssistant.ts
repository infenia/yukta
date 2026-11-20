// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useEffect, useRef, useState } from 'react';
import { runAgent } from '../lib/agentLoop';
import { listTools } from '../lib/mcpClient';
import { systemPrompt, type PageContext } from '../lib/pageContext';
import type { Call, ChatModel, Msg, ToolSpec } from '../types';

export interface Pending {
  call: Call;
  tool: ToolSpec;
}

/** One conversation: the messages, whether the model is working, and a tool waiting for approval. */
export function useAssistant(model: ChatModel, context: PageContext) {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const latest = useRef<Msg[]>([]);
  const controller = useRef<AbortController | null>(null);
  const decide = useRef<((ok: boolean) => void) | null>(null);

  const show = useCallback((next: Msg[]) => {
    latest.current = next;
    setMessages(next);
  }, []);

  const approve = useCallback((ok: boolean) => {
    decide.current?.(ok);
    decide.current = null;
    setPending(null);
  }, []);

  const stop = useCallback(() => {
    controller.current?.abort();
    approve(false);
  }, [approve]);

  // leaving the page ends the work in flight
  useEffect(() => stop, [stop]);

  const send = useCallback(
    async (text: string) => {
      const run = new AbortController();
      controller.current = run;
      setBusy(true);
      setError(null);
      const history: Msg[] = [...latest.current, { role: 'user', text }];
      show(history);
      try {
        await runAgent({
          model,
          system: systemPrompt(context),
          tools: await listTools(),
          history,
          signal: run.signal,
          confirm: (call, tool) =>
            new Promise<boolean>((resolve) => {
              decide.current = resolve;
              setPending({ call, tool });
            }),
          onChange: show,
        });
      } catch (failure) {
        setError((failure as Error).message);
      } finally {
        setBusy(false);
      }
    },
    [model, context, show]
  );

  return { messages, busy, error, pending, send, stop, approve };
}
