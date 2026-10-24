// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { Button } from '@/components/ui/button';

export default function HeaderLogo() {
  return (
    <Link to="/" className="no-underline">
      <Button
        variant="ghost"
        size="default"
        className="gap-spacing-sm hover:bg-muted flex items-center rounded-lg"
      >
        {/* Yukta mark: public/favicon.svg is generated from public/logo.svg (pnpm run favicons) */}
        <img src="/favicon.svg" alt="Yukta" className="size-8 object-contain" />
        <span className="text-on-surface text-headline-md">YUKTA</span>
      </Button>
    </Link>
  );
}
