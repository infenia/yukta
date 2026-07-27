// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/**
 * Controllable EventSource test double.
 *
 * Install per-test with `installMockEventSource()`, then drive streams via the static
 * instance registry: `MockEventSource.latest()?.emit({...})`.
 */
export class MockEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;

  static instances: MockEventSource[] = [];

  static latest(): MockEventSource {
    const source = MockEventSource.instances[MockEventSource.instances.length - 1];
    if (!source) {
      throw new Error('No MockEventSource instance created yet');
    }
    return source;
  }

  static byUrl(fragment: string): MockEventSource {
    const source = MockEventSource.instances
      .filter((candidate) => candidate.url.includes(fragment))
      .pop();
    if (!source) {
      throw new Error(`No MockEventSource matching url fragment: ${fragment}`);
    }
    return source;
  }

  static reset(): void {
    MockEventSource.instances = [];
  }

  readonly url: string;
  readyState = MockEventSource.CONNECTING;
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  /** Simulate the connection opening. */
  open(): void {
    this.readyState = MockEventSource.OPEN;
    this.onopen?.(new Event('open'));
  }

  /** Emit a message; objects are JSON-stringified like a real SSE data frame. */
  emit(data: unknown): void {
    const payload = typeof data === 'string' ? data : JSON.stringify(data);
    this.onmessage?.(new MessageEvent('message', { data: payload }));
  }

  /** Simulate a connection error. */
  emitError(): void {
    this.onerror?.(new Event('error'));
  }

  close(): void {
    this.readyState = MockEventSource.CLOSED;
  }
}

/** Replace the global EventSource with the controllable mock for the current test. */
export function installMockEventSource(): void {
  MockEventSource.reset();
  (globalThis as { EventSource: unknown }).EventSource = MockEventSource;
}
