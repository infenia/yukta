// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** A tool the model may call: an MCP tool, with the JSON Schema of its arguments. */
export interface ToolSpec {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  annotations?: { readOnlyHint?: boolean };
}

/** One tool call the model asked for. */
export interface Call {
  id: string;
  name: string;
  args: Record<string, unknown>;
}

/** The conversation, in the order the model sees it. */
export type Msg =
  | { role: 'user'; text: string }
  | { role: 'assistant'; text: string; calls: Call[] }
  | {
      role: 'tool';
      id: string;
      name: string;
      result: string;
      isError: boolean;
    };

/** What a model streams back: text as it comes, and each complete tool call. */
export type ModelEvent =
  { type: 'text'; delta: string } | { type: 'call'; call: Call };

/** A model the assistant can talk to, however it is reached. */
export interface ChatModel {
  /** Shown in the header, for example "Claude Sonnet 5.5 · your key". */
  label: string;
  /** The model runs in this browser: nothing leaves it. */
  onDevice?: boolean;
  /** How much of the model's context the last turn used, when the model can tell. */
  usage?(): { used: number; total: number } | null;
  stream(
    system: string,
    history: Msg[],
    tools: ToolSpec[],
    signal: AbortSignal
  ): AsyncIterable<ModelEvent>;
}
