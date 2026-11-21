// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ChatModel, ModelEvent, Msg } from '../../types';
import { streamProxy } from '../proxy';

/** The conversation in the Chat Completions shape. */
export function toOpenAi(system: string, history: Msg[]): unknown[] {
  return [
    { role: 'system', content: system },
    ...history.map((m) => {
      if (m.role === 'user') return { role: 'user', content: m.text };
      if (m.role === 'tool') {
        return { role: 'tool', tool_call_id: m.id, content: m.result };
      }
      return {
        role: 'assistant',
        content: m.text || null,
        tool_calls: m.calls.length
          ? m.calls.map((c) => ({
              id: c.id,
              type: 'function',
              function: { name: c.name, arguments: JSON.stringify(c.args) },
            }))
          : undefined,
      };
    }),
  ];
}

interface Partial {
  id: string;
  name: string;
  args: string;
}

/** Any OpenAI-compatible endpoint (OpenAI, Gemini, OpenRouter, Ollama) through the relay. */
export function openAiModel(config: {
  key: string;
  model: string;
  baseUrl?: string;
}): ChatModel {
  return {
    label: `${config.model} · your key`,
    async *stream(system, history, tools, signal): AsyncGenerator<ModelEvent> {
      const body = {
        model: config.model,
        stream: true,
        messages: toOpenAi(system, history),
        tools: tools.length
          ? tools.map((t) => ({
              type: 'function',
              function: {
                name: t.name,
                description: t.description,
                parameters: t.inputSchema,
              },
            }))
          : undefined,
      };
      const calls = new Map<number | string, Partial>();
      for await (const data of streamProxy('openai', body, config, signal)) {
        if (data === '[DONE]') break;
        const ev = JSON.parse(data);
        if (ev.error) throw new Error(ev.error.message);
        const delta = ev.choices?.[0]?.delta;
        if (!delta) continue;
        if (delta.content) yield { type: 'text', delta: delta.content };
        for (const part of delta.tool_calls ?? []) {
          // some compatible servers leave out the index of a call
          const key = part.index ?? part.id;
          const call = calls.get(key) ?? { id: '', name: '', args: '' };
          call.id = part.id ?? call.id;
          call.name = part.function?.name ?? call.name;
          call.args += part.function?.arguments ?? '';
          calls.set(key, call);
        }
      }
      for (const { id, name, args } of calls.values()) {
        yield {
          type: 'call',
          call: { id, name, args: JSON.parse(args || '{}') },
        };
      }
    },
  };
}
