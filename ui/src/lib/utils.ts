// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { clsx, type ClassValue } from 'clsx';
import { extendTailwindMerge } from 'tailwind-merge';

/* The named type and elevation utilities from index.css. Without this, tailwind-merge reads an
   unknown text-* class as a colour, so cn('text-label-sm', 'text-success') would drop the size. */
const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      'font-size': [
        {
          text: [
            'display-01',
            'headline-lg',
            'headline-lg-mobile',
            'headline-md',
            'body-md',
            'body-sm',
            'label-md',
            'label-sm',
            'code-sm',
            'overline',
          ],
        },
      ],
      shadow: [{ shadow: ['card', 'raised'] }],
    },
  },
});

/**
 * A fresh idempotency key. `crypto.randomUUID` exists only in secure contexts (https or
 * localhost), so over plain http on a LAN address it falls back to `getRandomValues`.
 */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join(
    ''
  );
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
