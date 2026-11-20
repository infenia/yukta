// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Call, ChatModel, Msg, ToolSpec } from '../types';
import { callTool } from './mcpClient';
import { isRead } from './toolPolicy';

/** The most model round trips for one question. */
export const MAX_STEPS = 8;

/** A tool result is cut here so one large listing cannot fill the model's context. */
export const RESULT_LIMIT = 16_000;

const clip = (text: string): string =>
  text.length > RESULT_LIMIT
    ? `${text.slice(0, RESULT_LIMIT)}\n… (${text.length - RESULT_LIMIT} more characters not shown)`
    : text;

export interface LoopOptions {
  model: ChatModel;
  system: string;
  tools: ToolSpec[];
  history: Msg[];
  signal: AbortSignal;
  /** Asks the person to approve a tool that may change something. */
  confirm: (call: Call, tool: ToolSpec) => Promise<boolean>;
  /** Called with the whole conversation after every change, for display. */
  onChange: (messages: Msg[]) => void;
}

/** Runs one tool call to a result message; every call gets one, so the history stays valid. */
async function answer(
  call: Call,
  { tools, signal, confirm }: LoopOptions,
  interrupted: boolean
): Promise<Msg> {
  const reply = (result: string, isError: boolean): Msg => ({
    role: 'tool',
    id: call.id,
    name: call.name,
    result,
    isError,
  });
  const tool = tools.find((t) => t.name === call.name);
  if (interrupted) return reply('Interrupted before it ran.', true);
  if (!tool) return reply(`There is no tool named ${call.name}.`, true);
  if (!isRead(tool) && !(await confirm(call, tool))) {
    return reply('The person declined this action.', true);
  }
  try {
    const { text, isError } = await callTool(call.name, call.args, signal);
    return reply(clip(text), isError);
  } catch (error) {
    return reply((error as Error).message, true);
  }
}

/**
 * Asks the model, runs the tools it calls, and asks again with the results, until it answers in
 * text or {@link MAX_STEPS} is reached. A tool that may change something waits for `confirm`.
 * A failure of the model is thrown after every call it had made has a result.
 */
export async function runAgent(options: LoopOptions): Promise<void> {
  const { model, system, tools, signal, onChange } = options;
  let messages = options.history;
  for (let step = 0; step < MAX_STEPS; step++) {
    let turn: Msg & { role: 'assistant' } = {
      role: 'assistant',
      text: '',
      calls: [],
    };
    const show = () => {
      messages = [...messages.slice(0, -1), turn];
      onChange(messages);
    };
    messages = [...messages, turn];
    onChange(messages);
    let failure: unknown;
    try {
      const sent = messages
        .slice(0, -1)
        .filter((m) => m.role !== 'assistant' || m.text || m.calls.length);
      for await (const event of model.stream(system, sent, tools, signal)) {
        turn =
          event.type === 'text'
            ? { ...turn, text: turn.text + event.delta }
            : { ...turn, calls: [...turn.calls, event.call] };
        show();
      }
    } catch (error) {
      failure = error;
    }
    for (const call of turn.calls) {
      const result = await answer(
        call,
        options,
        signal.aborted || failure !== undefined
      );
      messages = [...messages, result];
      onChange(messages);
    }
    if (failure !== undefined && !signal.aborted) throw failure;
    if (signal.aborted || turn.calls.length === 0) return;
  }
}
