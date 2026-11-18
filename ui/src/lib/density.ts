// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSyncExternalStore } from 'react';

export type Density = 'comfortable' | 'compact';

const KEY = 'density';
const listeners = new Set<() => void>();
// Kept for the page's lifetime so a choice still holds when storage is blocked.
let current: Density | null = null;

export function getDensity(): Density {
  try {
    const stored = localStorage.getItem(KEY);
    if (stored === 'compact' || stored === 'comfortable') return stored;
  } catch {
    // Storage is blocked: use the in-memory choice.
  }
  return current ?? 'comfortable';
}

/** Marks the page, so the `[data-density='compact']` rules in index.css apply. */
export function applyDensity(density: Density = getDensity()): void {
  document.documentElement.dataset.density = density;
}

// The stored choice applies as soon as the app loads this module (the header imports it).
applyDensity();

export function setDensity(density: Density): void {
  current = density;
  try {
    localStorage.setItem(KEY, density);
  } catch {
    // Not persisted; the choice still applies until the page is closed.
  }
  applyDensity(density);
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useDensity() {
  const density = useSyncExternalStore(subscribe, getDensity, getDensity);
  return { density, setDensity };
}
