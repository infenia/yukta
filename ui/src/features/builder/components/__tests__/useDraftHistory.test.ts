// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useDraftHistory } from '../useDraftHistory';

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(0);
});
afterEach(() => vi.useRealTimers());

const setup = (initial = 0) => renderHook(() => useDraftHistory(initial));

describe('useDraftHistory', () => {
  it('starts with nothing to undo or redo', () => {
    const { result } = setup(5);
    expect(result.current.value).toBe(5);
    expect(result.current.canUndo).toBe(false);
    expect(result.current.canRedo).toBe(false);
  });

  it('undoes and redoes each edit', () => {
    const { result } = setup();
    act(() => result.current.set((n) => n + 1));
    act(() => result.current.set((n) => n + 10));
    expect(result.current.value).toBe(11);

    act(() => result.current.undo());
    expect(result.current.value).toBe(1);
    expect(result.current.canRedo).toBe(true);
    act(() => result.current.undo());
    expect(result.current.value).toBe(0);
    expect(result.current.canUndo).toBe(false);

    act(() => result.current.redo());
    act(() => result.current.redo());
    expect(result.current.value).toBe(11);
    expect(result.current.canRedo).toBe(false);
  });

  it('does nothing when there is nothing to undo or redo', () => {
    const { result } = setup(3);
    act(() => result.current.undo());
    act(() => result.current.redo());
    expect(result.current.value).toBe(3);
  });

  it('drops what could be redone when something new is edited', () => {
    const { result } = setup();
    act(() => result.current.set(() => 1));
    act(() => result.current.undo());
    act(() => result.current.set(() => 2));
    expect(result.current.canRedo).toBe(false);
    act(() => result.current.undo());
    expect(result.current.value).toBe(0);
  });

  it('does not count an edit that changes nothing', () => {
    const { result } = setup(4);
    act(() => result.current.set((n) => n));
    expect(result.current.canUndo).toBe(false);
  });

  it('joins edits of one kind made close together, and only those', () => {
    const { result } = setup();
    act(() => result.current.set((n) => n + 1, 'text'));
    vi.setSystemTime(300);
    act(() => result.current.set((n) => n + 1, 'text'));
    expect(result.current.value).toBe(2);
    act(() => result.current.undo());
    expect(result.current.value).toBe(0);
    act(() => result.current.redo());

    // another kind, or too long after, is a step of its own
    vi.setSystemTime(400);
    act(() => result.current.set((n) => n + 1, 'other'));
    vi.setSystemTime(2000);
    act(() => result.current.set((n) => n + 1, 'other'));
    vi.setSystemTime(2100);
    act(() => result.current.set((n) => n + 1));
    act(() => result.current.set((n) => n + 1));
    expect(result.current.value).toBe(6);
    act(() => result.current.undo());
    act(() => result.current.undo());
    act(() => result.current.undo());
    expect(result.current.value).toBe(3);
  });

  it('keeps the last hundred steps', () => {
    const { result } = setup();
    for (let i = 0; i < 120; i += 1) {
      act(() => result.current.set((n) => n + 1));
    }
    for (let i = 0; i < 150; i += 1) {
      act(() => result.current.undo());
    }
    expect(result.current.value).toBe(20);
  });

  it('starts over on reset', () => {
    const { result } = setup();
    act(() => result.current.set(() => 1));
    act(() => result.current.undo());
    act(() => result.current.reset(9));
    expect(result.current.value).toBe(9);
    expect(result.current.canUndo).toBe(false);
    expect(result.current.canRedo).toBe(false);
  });
});
