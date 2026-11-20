// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// The part of Chrome's Prompt API (https://developer.chrome.com/docs/ai/prompt-api) the assistant
// uses, typed here instead of adding a dependency. Chrome 148 and later have it on the web.

export {};

declare global {
  interface NanoContent {
    role: 'system' | 'user' | 'assistant';
    content: string;
  }

  interface NanoCreateOptions {
    initialPrompts?: NanoContent[];
    expectedInputs?: { type: 'text'; languages: string[] }[];
    expectedOutputs?: { type: 'text'; languages: string[] }[];
    signal?: AbortSignal;
    monitor?: (monitor: EventTarget) => void;
  }

  interface NanoPromptOptions {
    signal?: AbortSignal;
    /** A JSON Schema the answer must follow; the result is then a JSON string. */
    responseConstraint?: unknown;
  }

  interface NanoSession {
    prompt(input: string, options?: NanoPromptOptions): Promise<string>;
    promptStreaming(
      input: string,
      options?: NanoPromptOptions
    ): ReadableStream<string>;
    clone(options?: { signal?: AbortSignal }): Promise<NanoSession>;
    destroy(): void;
  }

  interface NanoStatic {
    availability(
      options?: NanoCreateOptions
    ): Promise<'unavailable' | 'downloadable' | 'downloading' | 'available'>;
    create(options?: NanoCreateOptions): Promise<NanoSession>;
  }

  // the API is missing from other browsers, so the global may not exist
  // eslint-disable-next-line no-var
  var LanguageModel: NanoStatic | undefined;
}
