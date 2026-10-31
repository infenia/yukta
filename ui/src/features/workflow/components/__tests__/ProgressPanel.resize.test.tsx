// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ProgressPanel } from '../ProgressPanel';
import { createMockGraph } from '@/test/factories/workflowFactory';

// jsdom has no layout: clientWidth is 0
const setSize = (element: HTMLElement, name: string, value: number) =>
  Object.defineProperty(element, name, { configurable: true, value });

const renderPanel = () => {
  render(
    <ProgressPanel
      graph={createMockGraph()}
      progress={null}
      tasksByNode={{}}
      sessionId="s1"
      onClose={vi.fn()}
    />
  );
  return {
    panel: screen.getByTestId('progress-panel'),
    handle: screen.getByRole('separator', {
      name: 'Resize the progress panel',
    }),
  };
};

describe('ProgressPanel resize and collapse', () => {
  afterEach(() => localStorage.clear());

  it('opens at the default width, or the one kept from last time', () => {
    localStorage.setItem('yukta.progressWidth', '50');
    const { panel, handle } = renderPanel();
    // below the minimum: ignored
    expect(panel).toHaveStyle({ width: '380px' });
    expect(handle).toHaveAttribute('aria-valuenow', '380');
    expect(handle).toHaveAttribute('aria-valuemin', '280');
  });

  it('keeps the width the user chose', () => {
    localStorage.setItem('yukta.progressWidth', '450');
    expect(renderPanel().panel).toHaveStyle({ width: '450px' });
  });

  it('grows when its left edge is dragged left, within the window, and remembers it', () => {
    const { panel, handle } = renderPanel();
    // not laid out yet (setup.ts fakes 800): the drag starts from the width it was given
    setSize(panel, 'offsetWidth', 0);
    fireEvent.pointerDown(handle, { clientX: 800 });
    fireEvent.pointerMove(window, { clientX: 700 });
    expect(panel).toHaveStyle({ width: '480px' });
    // jsdom's window is 1024px wide, so the most is 60% of it
    fireEvent.pointerMove(window, { clientX: -1000 });
    expect(panel).toHaveStyle({ width: '614px' });
    fireEvent.pointerMove(window, { clientX: 5000 });
    expect(panel).toHaveStyle({ width: '280px' });
    fireEvent.pointerUp(window);
    // released: moving no longer resizes
    fireEvent.pointerMove(window, { clientX: 700 });
    expect(panel).toHaveStyle({ width: '280px' });
    expect(localStorage.getItem('yukta.progressWidth')).toBe('280');
  });

  it('starts a drag from the rendered width, and leaves the canvas room', () => {
    const { panel, handle } = renderPanel();
    setSize(panel, 'offsetWidth', 400);
    setSize(panel.parentElement as HTMLElement, 'clientWidth', 900);
    fireEvent.pointerDown(handle, { clientX: 800 });
    fireEvent.pointerMove(window, { clientX: 780 });
    expect(panel).toHaveStyle({ width: '420px' });
    fireEvent.pointerMove(window, { clientX: 0 });
    // the row less the 320px the canvas keeps
    expect(panel).toHaveStyle({ width: '580px' });
    fireEvent.pointerUp(window);
  });

  it('never goes below its minimum, even in a narrow row', () => {
    const { panel, handle } = renderPanel();
    setSize(panel.parentElement as HTMLElement, 'clientWidth', 500);
    fireEvent.pointerDown(handle, { clientX: 800 });
    fireEvent.pointerMove(window, { clientX: 0 });
    expect(panel).toHaveStyle({ width: '280px' });
    fireEvent.pointerUp(window);
  });

  it('resizes from the keyboard with the arrow keys', () => {
    const { panel, handle } = renderPanel();
    fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    expect(panel).toHaveStyle({ width: '404px' });
    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    expect(panel).toHaveStyle({ width: '356px' });
    fireEvent.keyDown(handle, { key: 'a' });
    expect(panel).toHaveStyle({ width: '356px' });
  });

  it('folds to a rail and back, and keeps that across reloads', async () => {
    const user = userEvent.setup();
    const { unmount } = render(
      <ProgressPanel
        graph={createMockGraph()}
        progress={null}
        tasksByNode={{}}
        sessionId="s1"
        onClose={vi.fn()}
      />
    );
    await user.click(
      screen.getByRole('button', { name: 'Collapse workflow progress' })
    );
    expect(screen.getByTestId('progress-panel')).toHaveClass('w-10');
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    expect(screen.queryByRole('separator')).not.toBeInTheDocument();
    unmount();

    // a new visit opens folded, and expands to the panel again
    render(
      <ProgressPanel
        graph={createMockGraph()}
        progress={null}
        tasksByNode={{}}
        sessionId="s1"
        onClose={vi.fn()}
      />
    );
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    await user.click(
      screen.getByRole('button', { name: 'Expand workflow progress' })
    );
    expect(screen.getByRole('tablist')).toBeInTheDocument();
    expect(localStorage.getItem('yukta.progressCollapsed')).toBe('false');
  });
});
