// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { buttonVariants } from '@/components/ui/button';
import { cn } from '@/lib/utils';

/** The Yukta mark and wordmark, linking home; a link styled as a button, not a button in a link. */
export default function HeaderLogo() {
  return (
    <Link
      to="/"
      className={cn(
        buttonVariants({ variant: 'ghost' }),
        'gap-spacing-sm hover:bg-muted -ml-2.5 rounded-lg no-underline sm:ml-0 sm:px-1.5'
      )}
    >
      {/* Yukta mark: public/favicon.svg is generated from public/logo.svg (pnpm run favicons) */}
      <img src="/favicon.svg" alt="" className="size-8 object-contain" />
      <span className="text-headline-md text-on-surface">Yukta</span>
    </Link>
  );
}
