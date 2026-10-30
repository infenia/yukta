// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, it, expect } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { LogsPanel } from '../LogsPanel';

// jsdom has no layout: clientHeight is 0 and setup.ts makes every offsetHeight 224
const setSize = (element: HTMLElement, name: string, value: number) =>
  Object.defineProperty(element, name, { configurable: true, value });

const renderDock = () => {
  render(<LogsPanel entries={[]} isStreaming={false} />);
  return {
    panel: screen.getByTestId('logs-panel'),
    handle: screen.getByRole('separator', { name: 'Resize the logs' }),
  };
};

describe('LogsPanel dock', () => {
  afterEach(() => localStorage.clear());

  it('opens at the default height, or the one kept from last time', () => {
    localStorage.setItem('yukta.logsHeight', '50');
    const { panel, handle } = renderDock();
    // below the minimum: ignored
    expect(panel).toHaveStyle({ height: '236px' });
    expect(handle).toHaveAttribute('aria-valuenow', '236');
    expect(handle).toHaveAttribute('aria-valuemin', '120');
  });

  it('keeps the height the user chose', () => {
    localStorage.setItem('yukta.logsHeight', '300');
    const { panel } = renderDock();
    expect(panel).toHaveStyle({ height: '300px' });
  });

  it('grows when its top edge is dragged up, within the window, and remembers it', () => {
    const { panel, handle } = renderDock();
    // not laid out yet: the drag starts from the height it was given
    setSize(panel, 'offsetHeight', 0);
    fireEvent.pointerDown(handle, { clientY: 500 });
    fireEvent.pointerMove(window, { clientY: 400 });
    expect(panel).toHaveStyle({ height: '336px' });
    // jsdom's window is 768px tall, so the most is 80% of it
    fireEvent.pointerMove(window, { clientY: -1000 });
    expect(panel).toHaveStyle({ height: '614px' });
    fireEvent.pointerMove(window, { clientY: 2000 });
    expect(panel).toHaveStyle({ height: '120px' });
    fireEvent.pointerUp(window);
    // released: moving no longer resizes
    fireEvent.pointerMove(window, { clientY: 400 });
    expect(panel).toHaveStyle({ height: '120px' });
    expect(localStorage.getItem('yukta.logsHeight')).toBe('120');
  });

  it('starts a drag from the rendered height, up to its column', () => {
    const { panel, handle } = renderDock();
    setSize(panel, 'offsetHeight', 400);
    setSize(panel.parentElement as HTMLElement, 'clientHeight', 450);
    fireEvent.pointerDown(handle, { clientY: 500 });
    fireEvent.pointerMove(window, { clientY: 480 });
    expect(panel).toHaveStyle({ height: '420px' });
    fireEvent.pointerMove(window, { clientY: 0 });
    expect(panel).toHaveStyle({ height: '450px' });
    fireEvent.pointerUp(window);
  });

  it('resizes from the keyboard with the arrow keys', () => {
    const { panel, handle } = renderDock();
    fireEvent.keyDown(handle, { key: 'ArrowUp' });
    expect(panel).toHaveStyle({ height: '260px' });
    fireEvent.keyDown(handle, { key: 'ArrowDown' });
    fireEvent.keyDown(handle, { key: 'ArrowDown' });
    expect(panel).toHaveStyle({ height: '212px' });
    fireEvent.keyDown(handle, { key: 'Enter' });
    expect(panel).toHaveStyle({ height: '212px' });
  });

  it('maximizes to its column and restores, and a drag ends the maximized state', () => {
    const { panel, handle } = renderDock();
    fireEvent.click(screen.getByRole('button', { name: 'Maximize the logs' }));
    expect(panel).toHaveStyle({ height: '100%' });
    fireEvent.click(
      screen.getByRole('button', { name: 'Restore the logs height' })
    );
    expect(panel).toHaveStyle({ height: '236px' });

    fireEvent.click(screen.getByRole('button', { name: 'Maximize the logs' }));
    fireEvent.keyDown(handle, { key: 'ArrowUp' });
    expect(panel).toHaveStyle({ height: '260px' });
    expect(
      screen.getByRole('button', { name: 'Maximize the logs' })
    ).toHaveAttribute('aria-pressed', 'false');
  });

  it('collapses to its toolbar, and maximizing a collapsed dock opens it', () => {
    const { panel } = renderDock();
    fireEvent.click(
      screen.getByRole('button', { name: 'Collapse execution logs' })
    );
    expect(panel.style.height).toBe('');
    expect(screen.queryByRole('separator')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Maximize the logs' }));
    expect(panel).toHaveStyle({ height: '100%' });
    expect(screen.getByRole('separator')).toBeInTheDocument();
  });
});
