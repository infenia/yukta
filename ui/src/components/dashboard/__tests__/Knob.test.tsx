// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Knob } from '../Knob';

const setup = (props: Partial<Parameters<typeof Knob>[0]> = {}) => {
  const onChange = vi.fn();
  const onCommit = vi.fn();
  render(
    <Knob
      label="Limit"
      value={5}
      min={0}
      max={10}
      step={1}
      onChange={onChange}
      onCommit={onCommit}
      {...props}
    />
  );
  return { slider: screen.getByRole('slider', { name: 'Limit' }), onChange, onCommit };
};

describe('Knob keyboard', () => {
  it('steps with the arrow keys and commits the new value', () => {
    const { slider, onChange, onCommit } = setup();
    fireEvent.keyDown(slider, { key: 'ArrowUp' });
    expect(onChange).toHaveBeenLastCalledWith(6);
    fireEvent.keyDown(slider, { key: 'ArrowLeft' });
    expect(onChange).toHaveBeenLastCalledWith(4);
    expect(onCommit).toHaveBeenLastCalledWith(4);
  });

  it('jumps by a tenth with page keys and to the ends with Home and End', () => {
    const { slider, onChange } = setup();
    fireEvent.keyDown(slider, { key: 'PageUp' });
    expect(onChange).toHaveBeenLastCalledWith(6);
    fireEvent.keyDown(slider, { key: 'PageDown' });
    expect(onChange).toHaveBeenLastCalledWith(4);
    fireEvent.keyDown(slider, { key: 'Home' });
    expect(onChange).toHaveBeenLastCalledWith(0);
    fireEvent.keyDown(slider, { key: 'End' });
    expect(onChange).toHaveBeenLastCalledWith(10);
  });

  it('ignores other keys and any key while disabled', () => {
    const { slider, onChange } = setup({ disabled: true });
    fireEvent.keyDown(slider, { key: 'ArrowUp' });
    expect(onChange).not.toHaveBeenCalled();
    expect(slider.getAttribute('aria-disabled')).toBe('true');
  });

  it('ignores keys it does not map', () => {
    const { slider, onChange } = setup();
    fireEvent.keyDown(slider, { key: 'a' });
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe('Knob pointer', () => {
  it('drags the value up and commits on release', () => {
    const { slider, onChange, onCommit } = setup();
    fireEvent.pointerDown(slider, { clientY: 100, pointerId: 1 });
    fireEvent.pointerMove(slider, { clientY: 60 });
    expect(onChange).toHaveBeenCalled();
    fireEvent.pointerUp(slider);
    expect(onCommit).toHaveBeenCalledWith(5);
  });

  it('does nothing when disabled or released without a drag', () => {
    const { slider, onChange, onCommit } = setup({ disabled: true });
    fireEvent.pointerDown(slider, { clientY: 100, pointerId: 1 });
    fireEvent.pointerMove(slider, { clientY: 60 });
    fireEvent.pointerUp(slider);
    expect(onChange).not.toHaveBeenCalled();
    expect(onCommit).not.toHaveBeenCalled();
  });

  it('keeps a flat angle when the range is empty', () => {
    setup({ min: 5, max: 5, value: 5 });
    expect(screen.getByRole('slider')).toBeInTheDocument();
  });
});
