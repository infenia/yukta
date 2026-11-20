// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { KioskView, KIOSK_CYCLE_MS } from '../KioskView';

// The panels fetch their own data; here each is a named stand-in so the groups can be told apart.
vi.mock('../HealthPanels', () => ({
  SystemPanel: () => <div>system</div>,
}));
vi.mock('../CapacityPanel', () => ({
  CapacityPanel: () => <div>capacity</div>,
}));
vi.mock('../ChartPanels', () => ({
  OverheadPanel: () => <div>overhead</div>,
  ThroughputPanel: () => <div>throughput</div>,
}));
vi.mock('../CommandStrip', () => ({
  CommandStrip: () => <div>strip</div>,
}));
vi.mock('../RunPanels', () => ({
  NodesPanel: () => <div>nodes</div>,
  LiveRunsPanel: () => <div>live-runs</div>,
}));
vi.mock('@/features/metrics/hooks', () => ({
  useDashboardParams: () => ({ scope: 'global', range: '1h', live: true }),
  useMetricStream: vi.fn(),
}));

describe('KioskView', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('leaves on Escape and on the exit button', () => {
    const onExit = vi.fn();
    render(<KioskView onExit={onExit} />);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onExit).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: /exit/i }));
    expect(onExit).toHaveBeenCalledTimes(2);
  });

  it('cycles the groups on a timer', () => {
    render(<KioskView onExit={vi.fn()} />);
    expect(screen.getByText('throughput')).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(KIOSK_CYCLE_MS));
    expect(screen.getByText('overhead')).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(KIOSK_CYCLE_MS));
    expect(screen.getByText('system')).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(KIOSK_CYCLE_MS));
    expect(screen.getByText('throughput')).toBeInTheDocument();
  });

  it('jumps to a group from its button', () => {
    render(<KioskView onExit={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Group 3' }));
    expect(
      screen
        .getByRole('button', { name: 'Group 3' })
        .getAttribute('aria-pressed')
    ).toBe('true');
    expect(screen.getByText('system')).toBeInTheDocument();
  });

  it('holds the current group while the pointer is over it', () => {
    render(<KioskView onExit={vi.fn()} />);
    const kiosk = screen.getByTestId('kiosk');
    fireEvent.mouseEnter(kiosk);
    act(() => vi.advanceTimersByTime(KIOSK_CYCLE_MS * 2));
    expect(screen.getByText('throughput')).toBeInTheDocument();
    fireEvent.mouseLeave(kiosk);
    act(() => vi.advanceTimersByTime(KIOSK_CYCLE_MS));
    expect(screen.getByText('overhead')).toBeInTheDocument();
  });

  it('renders in the body, above the header, and locks page scroll', () => {
    const { unmount } = render(<KioskView onExit={vi.fn()} />);
    const kiosk = screen.getByTestId('kiosk');
    expect(kiosk.parentElement).toBe(document.body);
    expect(kiosk.className).toContain('z-60');
    expect(document.body.style.overflow).toBe('hidden');
    unmount();
    expect(document.body.style.overflow).toBe('');
  });

  it('goes fullscreen, leaves when the browser leaves it, and exits it on close', async () => {
    const request = vi.fn().mockResolvedValue(undefined);
    const exit = vi.fn().mockResolvedValue(undefined);
    document.documentElement.requestFullscreen = request;
    document.exitFullscreen = exit;
    const onExit = vi.fn();
    const { unmount, rerender } = render(<KioskView onExit={onExit} />);
    expect(request).toHaveBeenCalled();
    fireEvent(document, new Event('fullscreenchange'));
    expect(onExit).toHaveBeenCalledTimes(1);
    Object.defineProperty(document, 'fullscreenElement', {
      value: document.documentElement,
      configurable: true,
    });
    fireEvent(document, new Event('fullscreenchange'));
    expect(onExit).toHaveBeenCalledTimes(1);
    // a re-render with a new callback keeps the fullscreen it has
    request.mockClear();
    rerender(<KioskView onExit={onExit} />);
    expect(request).not.toHaveBeenCalled();
    unmount();
    expect(exit).toHaveBeenCalled();
    Object.defineProperty(document, 'fullscreenElement', {
      value: null,
      configurable: true,
    });
  });

  it('stays an overlay when fullscreen is refused', async () => {
    document.documentElement.requestFullscreen = vi
      .fn()
      .mockRejectedValue(new Error('denied'));
    render(<KioskView onExit={vi.fn()} />);
    await Promise.resolve();
    expect(screen.getByTestId('kiosk')).toBeInTheDocument();
  });

  it('ignores keys other than Escape', () => {
    const onExit = vi.fn();
    render(<KioskView onExit={onExit} />);
    fireEvent.keyDown(window, { key: 'a' });
    expect(onExit).not.toHaveBeenCalled();
  });
});
