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
        className="gap-spacing-sm hover:bg-surface-container-low flex items-center"
      >
        {/* Yukta Logo Icon from public/favicon.svg */}
        <img
          src="/favicon.svg"
          alt="Yukta"
          className="h-7 w-7 object-contain rounded-3xl"
        />
        <span className="font-headline text-on-surface dark:text-on-surface text-lg font-semibold tracking-tight">
          YUKTA
        </span>
      </Button>
    </Link>
  );
}
