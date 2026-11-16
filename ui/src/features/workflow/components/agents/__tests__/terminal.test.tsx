// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createConversation } from '@/test/factories/conversationFactory';
import {
  installMockEventSource,
  MockEventSource,
} from '@/test/mocks/MockEventSource';
import { renderWithProviders } from '@/test/utils/testUtils';
import { sendTerminalInput, resizeTerminal } from '../../../api/conversations';
import { AgentTerminal, KEY_BATCH_MS } from '../AgentTerminal';
import { AgentsPanel } from '../AgentsPanel';

vi.mock('@xterm/xterm/css/xterm.css', () => ({}));

const terminals: FakeTerminal[] = [];
let fitSize: { cols: number; rows: number } | null = null;

class FakeTerminal {
  options: { disableStdin: boolean };
  writes: unknown[] = [];
  resets = 0;
  disposed = false;
  private data: ((data: string) => void) | null = null;
  private resize: ((size: { cols: number; rows: number }) => void) | null =
    null;
  constructor(options: { disableStdin: boolean }) {
    this.options = options;
    terminals.push(this);
  }
  loadAddon(addon: { terminal?: FakeTerminal }) {
    addon.terminal = this;
  }
  open() {}
  write(data: unknown) {
    this.writes.push(data);
  }
  reset() {
    this.resets += 1;
  }
  onData(callback: (data: string) => void) {
    this.data = callback;
    return { dispose: () => (this.data = null) };
  }
  onResize(callback: (size: { cols: number; rows: number }) => void) {
    this.resize = callback;
    return { dispose: () => (this.resize = null) };
  }
  dispose() {
    this.disposed = true;
  }
  type(text: string) {
    this.data?.(text);
  }
  resized(size: { cols: number; rows: number }) {
    this.resize?.(size);
  }
}

class FakeFit {
  terminal?: FakeTerminal;
  fit() {
    if (fitSize) this.terminal?.resized(fitSize);
  }
}

vi.mock('@xterm/xterm', () => ({
  Terminal: function (options: { disableStdin: boolean }) {
    return new FakeTerminal(options);
  },
}));
vi.mock('@xterm/addon-fit', () => ({
  FitAddon: function () {
    return new FakeFit();
  },
}));
vi.mock('../../../api/conversations', async (original) => ({
  ...(await original<typeof import('../../../api/conversations')>()),
  sendTerminalInput: vi.fn(() => Promise.resolve()),
  resizeTerminal: vi.fn(() => Promise.resolve()),
}));

const pty = (over = {}) =>
  createConversation({
    id: 'c9',
    nodeId: 'build-fix',
    harness: 'terminal',
    protocol: 'PTY',
    capabilities: ['message', 'terminal'],
    ...over,
  });

const observers: (() => void)[] = [];
const RealResizeObserver = globalThis.ResizeObserver;

beforeEach(() => {
  installMockEventSource();
  terminals.length = 0;
  fitSize = null;
  observers.length = 0;
  vi.mocked(sendTerminalInput).mockClear().mockResolvedValue(undefined);
  vi.mocked(resizeTerminal).mockClear().mockResolvedValue(undefined);
  globalThis.ResizeObserver = class {
    constructor(callback: () => void) {
      observers.push(callback);
    }
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

afterEach(() => {
  vi.useRealTimers();
  globalThis.ResizeObserver = RealResizeObserver;
});

const event = (data: Record<string, unknown>, kind = 'TERMINAL') => ({
  seq: 1,
  conversationId: 'c9',
  timestamp: 't',
  kind,
  itemId: null,
  data,
});

describe('AgentTerminal', () => {
  it('opens a terminal with its warning and size, and follows the terminal events of the agent', () => {
    render(<AgentTerminal conversation={pty()} canType />);

    expect(
      screen.getByRole('region', { name: 'Terminal of build-fix' })
    ).toBeInTheDocument();
    expect(screen.getByText(/not reviewed/)).toBeInTheDocument();
    expect(screen.getByText(/80 columns × 24 rows/)).toBeInTheDocument();
    expect(screen.getByText(/Typing goes to the terminal/)).toBeInTheDocument();
    expect(MockEventSource.latest().url).toBe(
      '/api/executions/exec-1/conversations/stream?tree=true&terminal=true&kinds=TERMINAL&conversationId=c9'
    );
  });

  it('writes the output, and starts again from a screen', () => {
    render(<AgentTerminal conversation={pty()} canType />);
    const source = MockEventSource.latest();
    const fake = terminals[0];

    act(() => {
      source.emitNamed('event', event({ data: btoa('hi') }));
      source.emitNamed('event', event({ data: '%%not base64%%' }));
      source.emitNamed(
        'event',
        event({ screen: 'one\ntwo', cursorRow: 1, cursorCol: 3 })
      );
      source.emitNamed('event', event({ screen: 'x' }));
      source.emitNamed('event', event({ cols: 80 }));
      source.emitNamed('event', event({ data: btoa('no') }, 'MESSAGE'));
    });

    expect(fake.writes[0]).toEqual(new Uint8Array([104, 105]));
    expect(fake.writes[1]).toEqual(new Uint8Array());
    expect(fake.resets).toBe(2);
    expect(fake.writes[2]).toBe('one\r\ntwo');
    expect(fake.writes[3]).toBe('\x1b[2;4H');
    expect(fake.writes[5]).toBe('\x1b[1;1H');
    expect(fake.writes).toHaveLength(6);
  });

  it('sends the keys typed within a moment in one request', () => {
    vi.useFakeTimers();
    render(<AgentTerminal conversation={pty()} canType />);

    terminals[0].type('l');
    terminals[0].type('s é');
    expect(sendTerminalInput).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(KEY_BATCH_MS));

    expect(sendTerminalInput).toHaveBeenCalledTimes(1);
    const [, bytes] = vi.mocked(sendTerminalInput).mock.calls[0];
    expect(Array.from(bytes)).toEqual(
      Array.from(new TextEncoder().encode('ls é'))
    );
    expect(vi.mocked(sendTerminalInput).mock.calls[0][0]).toBe('c9');
    terminals[0].type('x');
    act(() => vi.advanceTimersByTime(KEY_BATCH_MS));
    expect(sendTerminalInput).toHaveBeenCalledTimes(2);
  });

  it('tells the agent how big the view is when it changes', () => {
    fitSize = { cols: 100, rows: 30 };
    render(<AgentTerminal conversation={pty()} canType />);

    expect(resizeTerminal).toHaveBeenCalledWith('c9', 100, 30);
    expect(screen.getByText(/100 columns × 30 rows/)).toBeInTheDocument();

    fitSize = { cols: 120, rows: 40 };
    act(() => observers[0]());
    expect(resizeTerminal).toHaveBeenLastCalledWith('c9', 120, 40);
  });

  it('is a view only when the person may not type, and sends no key and no size', () => {
    vi.useFakeTimers();
    fitSize = { cols: 100, rows: 30 };
    render(<AgentTerminal conversation={pty()} canType={false} />);

    terminals[0].type('rm -rf');
    act(() => vi.advanceTimersByTime(KEY_BATCH_MS * 2));

    expect(terminals[0].options.disableStdin).toBe(true);
    expect(sendTerminalInput).not.toHaveBeenCalled();
    expect(resizeTerminal).not.toHaveBeenCalled();
    expect(
      screen.getByText(/You may not type into this terminal/)
    ).toBeInTheDocument();
  });

  it('keeps its last screen and stops typing when the agent ends', () => {
    const { rerender } = render(<AgentTerminal conversation={pty()} canType />);
    expect(terminals[0].options.disableStdin).toBe(false);

    rerender(
      <AgentTerminal
        conversation={pty({ state: 'ENDED', outcome: 'success', endedAt: 't' })}
        canType
      />
    );

    expect(terminals[0].options.disableStdin).toBe(true);
    expect(screen.getByText(/last screen/)).toBeInTheDocument();
  });

  it('stops typing when the server refuses the keys, and cleans up', async () => {
    vi.mocked(sendTerminalInput).mockRejectedValue(new Error('403'));
    const { unmount } = render(<AgentTerminal conversation={pty()} canType />);

    terminals[0].type('x');

    await waitFor(() =>
      expect(
        screen.getByText(/You may not type into this terminal/)
      ).toBeInTheDocument()
    );
    unmount();
    expect(terminals[0].disposed).toBe(true);
  });

  it('is what the panel shows for a terminal agent, in place of the timeline', async () => {
    renderWithProviders(<AgentsPanel conversations={[pty()]} items={[]} />);
    expect(screen.getByRole('log')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /build-fix/ }));
    expect(screen.queryByRole('log')).not.toBeInTheDocument();

    expect(
      await screen.findByRole('region', { name: 'Terminal of build-fix' })
    ).toBeInTheDocument();
  });
});
