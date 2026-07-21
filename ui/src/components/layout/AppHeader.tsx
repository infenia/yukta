// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import HeaderLogo from './HeaderLogo';
import BreadcrumbNav from './BreadcrumbNav';
import ThemeToggle from './ThemeToggle';

export default function AppHeader() {
  return (
    <header className="fixed inset-x-0 top-0 z-50 border-b border-outline-variant bg-surface">
      {/* Top row: Logo + Theme Toggle */}
      <div className="px-spacing-md flex h-12 w-full items-center justify-between pr-2">
        <HeaderLogo />
        <ThemeToggle />
      </div>

      {/* Bottom row: Breadcrumb Navigation */}
      <BreadcrumbNav />
    </header>
  );
}
