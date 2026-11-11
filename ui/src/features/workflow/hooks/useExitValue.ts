// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useState } from 'react';

/** How long a closing drawer plays its exit animation (the `duration-150` of its classes). */
export const EXIT_MS = 150;

/**
 * Keeps the last non-null `value` mounted for `ms` after it turns null, so an exit animation can
 * play: `shown` is what to render, `closing` is true during that time.
 */
export function useExitValue<T>(value: T | null, ms = EXIT_MS) {
  const [shown, setShown] = useState<T | null>(value);
  if (value !== null && value !== shown) setShown(value);
  useEffect(() => {
    if (value !== null) return undefined;
    const timer = setTimeout(() => setShown(null), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return { shown, closing: shown !== null && value === null };
}
