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

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
