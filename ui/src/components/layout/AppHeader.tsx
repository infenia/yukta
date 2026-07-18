// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import ThemeToggle from './ThemeToggle';

export default function AppHeader() {
  return (
    <header className="border-b border-outline">
      {/* Top row: logo */}
      <div className="h-16 flex items-center px-spacing-md">
        <Link to="/" className="font-headline text-heading-md">
          Yukta
        </Link>
        <div className="ml-auto">
          <ThemeToggle />
        </div>
      </div>

      {/* Bottom row: breadcrumb */}
      <div className="h-12 flex items-center px-spacing-md bg-surface-container-low text-body-sm">
        <nav aria-label="Breadcrumb" className="flex items-center gap-2">
          <span>Sessions</span>
        </nav>
      </div>
    </header>
  );
}
