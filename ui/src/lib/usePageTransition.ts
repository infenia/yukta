// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, type RefObject } from 'react';

/** The docs theme's reveal: a short fade and 8px rise on its one easing curve. */
const KEYFRAMES: Keyframe[] = [
  { opacity: 0, transform: 'translateY(8px)' },
  { opacity: 1, transform: 'none' },
];
const TIMING: KeyframeAnimationOptions = {
  duration: 350,
  easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)',
};

/**
 * Fades the element in whenever `key` (the route path) changes. It animates in place with the
 * Web Animations API, so the page is not remounted and keeps its state. Does nothing for users
 * who prefer reduced motion, or where the API is missing.
 */
export function usePageTransition(
  ref: RefObject<HTMLElement | null>,
  key: string
) {
  useEffect(() => {
    const element = ref.current;
    if (
      !element ||
      typeof element.animate !== 'function' ||
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    ) {
      return;
    }
    element.animate(KEYFRAMES, TIMING);
  }, [ref, key]);
}
