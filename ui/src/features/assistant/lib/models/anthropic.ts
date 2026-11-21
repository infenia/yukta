// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ChatModel, ModelEvent, Msg } from '../../types';
import { streamProxy } from '../proxy';

interface Block {
  role: 'user' | 'assistant';
  content: unknown;
}

/** The conversation in the Messages API shape: tool results ride in one user message. */
export function toAnthropic(history: Msg[]): Block[] {
  const out: Block[] = [];
  for (const m of history) {
    if (m.role === 'user') {
      out.push({ role: 'user', content: m.text });
    } else if (m.role === 'assistant') {
      out.push({
        role: 'assistant',
        content: [
          ...(m.text ? [{ type: 'text', text: m.text }] : []),
          ...m.calls.map((c) => ({
            type: 'tool_use',
            id: c.id,
            name: c.name,
            input: c.args,
          })),
        ],
      });
    } else {
      const result = {
        type: 'tool_result',
        tool_use_id: m.id,
        content: m.result,
        is_error: m.isError,
      };
      const last = out[out.length - 1] as Block | undefined;
      if (last?.role === 'user' && Array.isArray(last.content)) {
        last.content.push(result);
      } else {
        out.push({ role: 'user', content: [result] });
      }
    }
  }
  return out;
}

/** Claude through the relay, with native tool calling. */
export function anthropicModel(config: {
  key: string;
  model: string;
}): ChatModel {
  return {
    label: `${config.model} · your key`,
    async *stream(system, history, tools, signal): AsyncGenerator<ModelEvent> {
      const body = {
        model: config.model,
        max_tokens: 4096,
        stream: true,
        system,
        messages: toAnthropic(history),
        tools: tools.map((t) => ({
          name: t.name,
          description: t.description,
          input_schema: t.inputSchema,
        })),
      };
      const open = new Map<
        number,
        { id: string; name: string; json: string }
      >();
      for await (const data of streamProxy('anthropic', body, config, signal)) {
        const ev = JSON.parse(data);
        if (ev.type === 'error') throw new Error(ev.error.message);
        if (
          ev.type === 'content_block_start' &&
          ev.content_block.type === 'tool_use'
        ) {
          open.set(ev.index, {
            id: ev.content_block.id,
            name: ev.content_block.name,
            json: '',
          });
        } else if (ev.type === 'content_block_delta') {
          if (ev.delta.type === 'text_delta') {
            yield { type: 'text', delta: ev.delta.text };
          } else if (ev.delta.type === 'input_json_delta') {
            (open.get(ev.index) as { json: string }).json +=
              ev.delta.partial_json;
          }
        } else if (ev.type === 'content_block_stop' && open.has(ev.index)) {
          const { id, name, json } = open.get(ev.index) as {
            id: string;
            name: string;
            json: string;
          };
          open.delete(ev.index);
          yield {
            type: 'call',
            call: { id, name, args: JSON.parse(json || '{}') },
          };
        }
      }
    },
  };
}
