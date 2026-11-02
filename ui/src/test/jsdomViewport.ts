// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, beforeEach } from 'vitest';

const VIEWPORT = { clientHeight: 224, clientWidth: 800 } as const;

/**
 * jsdom has no layout, and @tanstack/react-virtual 3.14.22+ sizes its scroll element from
 * clientHeight/clientWidth (earlier versions read offset*), so a virtualised list renders no rows.
 * Call this inside a describe block to give every element a viewport for its tests only:
 * LogsPanel's dock reads clientHeight for its drag limit, so it must not be global.
 */
export function useJsdomViewport(): void {
  const keys = Object.keys(VIEWPORT) as (keyof typeof VIEWPORT)[];
  let saved: [string, PropertyDescriptor | undefined][] = [];

  beforeEach(() => {
    saved = keys.map((key) => [
      key,
      Object.getOwnPropertyDescriptor(HTMLElement.prototype, key),
    ]);
    for (const key of keys) {
      Object.defineProperty(HTMLElement.prototype, key, {
        configurable: true,
        value: VIEWPORT[key],
      });
    }
  });

  afterEach(() => {
    for (const [key, descriptor] of saved) {
      if (descriptor) {
        Object.defineProperty(HTMLElement.prototype, key, descriptor);
      } else {
        Reflect.deleteProperty(HTMLElement.prototype, key);
      }
    }
  });
}
