// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { authHeaders, getApiKey, requireApiKey } from './apiKey';

/**
 * The part of EventSource the app uses, over fetch so it can send the Authorization header
 * (a browser EventSource cannot). Used only when an API key is set. Parses "data:" lines.
 */
export class KeyedEventSource {
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: (() => void) | null = null;
  private readonly abort = new AbortController();

  constructor(url: string) {
    void this.run(url);
  }

  close(): void {
    this.abort.abort();
  }

  private async run(url: string): Promise<void> {
    try {
      const response = await fetch(url, {
        headers: { Accept: 'text/event-stream', ...authHeaders() },
        signal: this.abort.signal,
      });
      if (response.status === 401) {
        requireApiKey();
      }
      if (!response.ok || !response.body) {
        throw new Error(`stream failed: ${response.status}`);
      }
      this.onopen?.();
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        buffer += value;
        const frames = buffer.split(/\r?\n\r?\n/);
        buffer = frames.splice(-1)[0] as string;
        frames.forEach((frame) => this.emit(frame));
      }
      throw new Error('stream ended');
    } catch {
      if (!this.abort.signal.aborted) {
        this.onerror?.();
      }
    }
  }

  private emit(frame: string): void {
    const data = frame
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).replace(/^ /, ''))
      .join('\n');
    if (data) {
      this.onmessage?.(new MessageEvent('message', { data }));
    }
  }
}

/** A keyed stream when an API key is set, the native EventSource otherwise. */
export function openEventStream(url: string): EventSource | KeyedEventSource {
  return getApiKey() ? new KeyedEventSource(url) : new EventSource(url);
}
