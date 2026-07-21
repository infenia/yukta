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
        className="gap-spacing-sm flex items-center hover:bg-surface-container-low"
      >
        {/* Yukta Logo Icon from public/favicon.svg */}
        <img
          src="/favicon.svg"
          alt="Yukta"
          className="size-7 rounded-3xl object-contain"
        />
        <span className="font-headline text-lg font-semibold tracking-tight text-on-surface dark:text-on-surface">
          YUKTA
        </span>
      </Button>
    </Link>
  );
}
