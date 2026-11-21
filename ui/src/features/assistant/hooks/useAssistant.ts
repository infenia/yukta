// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useEffect, useRef, useState } from 'react';
import { runAgent } from '../lib/agentLoop';
import { loadChat, saveChat } from '../lib/history';
import { SEARCH_DOCS, searchDocs } from '../lib/docsSearch';
import { listTools } from '../lib/mcpClient';
import { systemPrompt, type PageContext } from '../lib/pageContext';
import type { Call, ChatModel, Msg, ToolSpec } from '../types';

export interface Pending {
  call: Call;
  tool: ToolSpec;
}

/** One conversation: the messages, whether the model is working, and a tool waiting for approval. */
export function useAssistant(model: ChatModel, context: PageContext) {
  const [messages, setMessages] = useState<Msg[]>(loadChat);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const latest = useRef<Msg[]>(messages);
  const controller = useRef<AbortController | null>(null);
  const decide = useRef<((ok: boolean) => void) | null>(null);

  const show = useCallback((next: Msg[]) => {
    latest.current = next;
    setMessages(next);
    saveChat(next);
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
          tools: [...(await listTools()), SEARCH_DOCS],
          local: { search_docs: searchDocs },
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

  /** Asks the last question again, dropping the answer it got. */
  const retry = useCallback(() => {
    const at = latest.current.map((m) => m.role).lastIndexOf('user');
    const question = latest.current[at] as Extract<Msg, { role: 'user' }>;
    show(latest.current.slice(0, at));
    return send(question.text);
  }, [send, show]);

  return { messages, busy, error, pending, send, retry, stop, approve };
}
