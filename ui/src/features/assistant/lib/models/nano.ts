// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Call, ChatModel, ModelEvent, Msg, ToolSpec } from '../../types';

/** Where the on-device model is: not in this browser, not on this device, or how far along. */
export type NanoState =
  | 'checking'
  | 'unsupported'
  | 'unavailable'
  | 'downloadable'
  | 'downloading'
  | 'available';

const OPTIONS = {
  expectedInputs: [{ type: 'text' as const, languages: ['en'] }],
  expectedOutputs: [{ type: 'text' as const, languages: ['en'] }],
};

/**
 * The tools offered to the on-device model. Its context is small and it picks badly from sixty,
 * so it gets the documentation search, the ones a question about runs needs, and the two that start and steer them.
 */
export const NANO_TOOLS = new Set([
  'search_docs',
  'list_sessions',
  'get_session_details',
  'get_workflow_details',
  'get_workflow_status',
  'get_execution_result',
  'get_execution_tree',
  'get_execution_logs',
  'get_node_output',
  'list_pending_inputs',
  'get_metrics_snapshot',
  'whoami',
  'start_workflow',
  'control_workflow',
]);

/** A tool result is cut here: the model's whole context is only a few thousand tokens. */
const RESULT_LIMIT = 4000;

/** Most tool calls for one question; a small model tends to ask again and again. */
const MAX_TOOL_RUNS = 3;

let sequence = 0;
let usage: { used: number; total: number } | null = null;

export async function nanoAvailability(): Promise<NanoState> {
  if (typeof LanguageModel === 'undefined') return 'unsupported';
  try {
    return await LanguageModel.availability(OPTIONS);
  } catch {
    return 'unavailable';
  }
}

/**
 * Starts the model download (Chrome asks for a user gesture, so call this from a click) and
 * reports the fraction done.
 */
export async function downloadNano(
  onProgress: (fraction: number) => void
): Promise<void> {
  const session = await (LanguageModel as NanoStatic).create({
    ...OPTIONS,
    monitor(monitor) {
      monitor.addEventListener('downloadprogress', (event) =>
        onProgress((event as Event & { loaded: number }).loaded)
      );
    },
  });
  session.destroy();
}

/** The conversation as alternating user and assistant turns; tool results are user turns. */
function turns(history: Msg[]): NanoContent[] {
  const out: NanoContent[] = [];
  for (const m of history) {
    const role = m.role === 'assistant' ? 'assistant' : 'user';
    const content =
      m.role === 'user'
        ? m.text
        : m.role === 'assistant'
          ? [
              m.text,
              ...m.calls.map(
                (c) => `(called ${c.name} ${JSON.stringify(c.args)})`
              ),
            ]
              .filter(Boolean)
              .join('\n')
          : `Result of ${m.name}${m.isError ? ' (failed)' : ''}:\n${m.result.slice(0, RESULT_LIMIT)}`;
    const last = out[out.length - 1] as NanoContent | undefined;
    if (last?.role === role) {
      last.content += `\n\n${content}`;
    } else {
      out.push({ role, content });
    }
  }
  return out;
}

/** How many tools ran since the person last wrote. */
function toolRuns(history: Msg[]): number {
  const lastUser = history.map((m) => m.role).lastIndexOf('user');
  return history.slice(lastUser + 1).filter((m) => m.role === 'tool').length;
}

/**
 * Asks, with the answer held to a JSON Schema, which tool the last message needs (or none) and
 * then for its arguments, held to the tool's own schema. The built-in model has no tool calling
 * of its own, so the schema is what keeps the choice and the arguments well-formed.
 */
async function plan(
  base: NanoSession,
  tools: ToolSpec[],
  signal: AbortSignal
): Promise<Call | null> {
  const probe = await base.clone({ signal });
  try {
    const catalog = tools
      .map((t) => `- ${t.name}: ${t.description.slice(0, 120)}`)
      .join('\n');
    const picked = JSON.parse(
      await probe.prompt(
        `Tools:\n${catalog}\nIf the last message needs one of these tools to be answered, name it; otherwise answer "none".`,
        {
          signal,
          responseConstraint: {
            type: 'object',
            properties: {
              tool: {
                type: 'string',
                enum: [...tools.map((t) => t.name), 'none'],
              },
            },
            required: ['tool'],
            additionalProperties: false,
          },
        }
      )
    ).tool;
    const tool = tools.find((t) => t.name === picked);
    if (!tool) return null;
    let args: Record<string, unknown> = {};
    try {
      args = JSON.parse(
        await probe.prompt(`Give the arguments of ${tool.name} as JSON.`, {
          signal,
          responseConstraint: tool.inputSchema,
        })
      );
    } catch (error) {
      // a schema the browser cannot enforce, or no arguments: call with none, but not when stopped
      if (signal.aborted) throw error;
    }
    return { id: `nano-${++sequence}`, name: tool.name, args };
  } finally {
    probe.destroy();
  }
}

async function* run(
  system: string,
  history: Msg[],
  tools: ToolSpec[],
  signal: AbortSignal
): AsyncGenerator<ModelEvent> {
  const all = turns(history);
  const input = (all.pop() as NanoContent).content;
  const base = await (LanguageModel as NanoStatic).create({
    ...OPTIONS,
    signal,
    initialPrompts: [{ role: 'system', content: system }, ...all],
  });
  try {
    if (tools.length > 0 && toolRuns(history) < MAX_TOOL_RUNS) {
      const call = await plan(base, tools, signal);
      if (call) {
        yield { type: 'call', call };
        return;
      }
    }
    // chunks are the new text; if a build sends the text so far, take what is new
    let sofar = '';
    for await (const chunk of base.promptStreaming(input, { signal })) {
      const cumulative = sofar !== '' && chunk.startsWith(sofar);
      const delta = cumulative ? chunk.slice(sofar.length) : chunk;
      sofar = cumulative ? chunk : sofar + chunk;
      if (delta) yield { type: 'text', delta };
    }
  } finally {
    usage = { used: base.contextUsage, total: base.contextWindow };
    base.destroy();
  }
}

/** Gemini Nano in the browser: free, on this device, no key. */
export function nanoModel(): ChatModel {
  return {
    label: 'Gemini Nano · on this device',
    onDevice: true,
    usage: () => usage,
    async *stream(system, history, tools, signal) {
      const usable = tools.filter((t) => NANO_TOOLS.has(t.name));
      let started = false;
      try {
        for await (const event of run(system, history, usable, signal)) {
          started = true;
          yield event;
        }
      } catch (error) {
        // the conversation does not fit: try again with only the last few turns
        if (started || (error as Error).name !== 'QuotaExceededError')
          throw error;
        yield* run(system, history.slice(-4), usable, signal);
      }
    },
  };
}
