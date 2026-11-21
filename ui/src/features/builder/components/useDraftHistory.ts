// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';

/** The most steps back that can be undone. */
const MAX_STEPS = 100;
/** Edits of one kind this close together are one step, so typing a word is one undo. */
const COALESCE_MS = 500;

interface History<T> {
  past: T[];
  present: T;
  future: T[];
  /** What the last edit was, and when, so the next one can join it. */
  key: string | null;
  at: number;
}

/**
 * A value with undo and redo. `set` makes a new value; given a `key`, an edit under the same key
 * within half a second of the last one replaces it instead of adding a step. A new edit drops
 * what could have been redone.
 */
export function useDraftHistory<T>(initial: T) {
  const [state, setState] = useState<History<T>>({
    past: [],
    present: initial,
    future: [],
    key: null,
    at: 0,
  });

  const set = (change: (current: T) => T, key: string | null = null) =>
    setState((s) => {
      const next = change(s.present);
      if (next === s.present) return s;
      const now = Date.now();
      const joins = key !== null && key === s.key && now - s.at < COALESCE_MS;
      return {
        past: joins ? s.past : [...s.past, s.present].slice(-MAX_STEPS),
        present: next,
        future: [],
        key,
        at: now,
      };
    });

  const undo = () =>
    setState((s) =>
      s.past.length === 0
        ? s
        : {
            past: s.past.slice(0, -1),
            present: s.past[s.past.length - 1],
            future: [s.present, ...s.future],
            key: null,
            at: 0,
          }
    );

  const redo = () =>
    setState((s) =>
      s.future.length === 0
        ? s
        : {
            past: [...s.past, s.present],
            present: s.future[0],
            future: s.future.slice(1),
            key: null,
            at: 0,
          }
    );

  /** Starts over from `value`, with nothing to undo. */
  const reset = (value: T) =>
    setState({ past: [], present: value, future: [], key: null, at: 0 });

  return {
    value: state.present,
    set,
    undo,
    redo,
    reset,
    canUndo: state.past.length > 0,
    canRedo: state.future.length > 0,
  };
}
