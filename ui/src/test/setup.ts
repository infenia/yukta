// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import '@testing-library/jest-dom';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

// Mock window.matchMedia if not available
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

// jsdom lacks ResizeObserver, needed by React Flow
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
Object.defineProperty(globalThis, 'ResizeObserver', {
  writable: true,
  value: ResizeObserverStub,
});

// jsdom lacks DOMMatrixReadOnly, needed by React Flow's zoom/pan transform parsing
class DOMMatrixReadOnlyStub {
  m22 = 1;
  constructor(transform?: string) {
    const scale = transform?.match(/scale\(([^)]+)\)/)?.[1];
    if (scale) {
      this.m22 = Number(scale);
    }
  }
}
Object.defineProperty(globalThis, 'DOMMatrixReadOnly', {
  writable: true,
  value: DOMMatrixReadOnlyStub,
});

// Default no-op EventSource; SSE tests replace it with MockEventSource per-test
class NoopEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  readyState = 0;
  readonly url: string;
  constructor(url: string) {
    this.url = url;
  }
  close() {
    this.readyState = 2;
  }
  addEventListener(_type: string, _listener: EventListener | EventListenerObject): void {
    // No-op; test code uses on* handlers instead
  }
  removeEventListener(_type: string, _listener: EventListener | EventListenerObject): void {
    // No-op; test code uses on* handlers instead
  }
}
Object.defineProperty(globalThis, 'EventSource', {
  writable: true,
  configurable: true,
  value: NoopEventSource,
});
