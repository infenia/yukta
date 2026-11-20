// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PanelResizer } from '../PanelResizer';

const setup = (width = 400) => {
  const onWidth = vi.fn();
  render(<PanelResizer width={width} min={300} max={600} onWidth={onWidth} />);
  return { onWidth, handle: screen.getByRole('separator') };
};

describe('PanelResizer', () => {
  it('widens when dragged left, narrows when dragged right, within the limits', () => {
    const { onWidth, handle } = setup();
    fireEvent.pointerDown(handle, { clientX: 500 });
    expect(document.body.style.cursor).toBe('col-resize');

    fireEvent.pointerMove(window, { clientX: 450 });
    expect(onWidth).toHaveBeenLastCalledWith(450);
    fireEvent.pointerMove(window, { clientX: 100 });
    expect(onWidth).toHaveBeenLastCalledWith(600);
    fireEvent.pointerMove(window, { clientX: 900 });
    expect(onWidth).toHaveBeenLastCalledWith(300);

    fireEvent.pointerUp(window);
    expect(document.body.style.cursor).toBe('');
    onWidth.mockClear();
    fireEvent.pointerMove(window, { clientX: 450 });
    expect(onWidth).not.toHaveBeenCalled();
  });

  it('resizes from the keyboard and ignores other keys', () => {
    const { onWidth, handle } = setup();
    fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    expect(onWidth).toHaveBeenLastCalledWith(424);
    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    expect(onWidth).toHaveBeenLastCalledWith(376);
    fireEvent.keyDown(handle, { key: 'Home' });
    expect(onWidth).toHaveBeenLastCalledWith(600);
    fireEvent.keyDown(handle, { key: 'End' });
    expect(onWidth).toHaveBeenLastCalledWith(300);

    onWidth.mockClear();
    fireEvent.keyDown(handle, { key: 'a' });
    expect(onWidth).not.toHaveBeenCalled();
  });
});
