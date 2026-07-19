// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import HeaderLogo from './HeaderLogo';
import BreadcrumbNav from './BreadcrumbNav';
import ThemeToggle from './ThemeToggle';

export default function AppHeader() {
  return (
    <header className="bg-surface border-outline-variant fixed top-0 right-0 left-0 z-50 border-b">
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
