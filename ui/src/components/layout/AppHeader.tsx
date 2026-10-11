// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { NavLink } from 'react-router';
import HeaderLogo from './HeaderLogo';
import BreadcrumbNav from './BreadcrumbNav';
import ThemeToggle from './ThemeToggle';

export default function AppHeader() {
  return (
    <header className="fixed inset-x-0 top-0 z-50 border-b border-outline-variant bg-surface">
      {/* Top row: Logo + page actions + Theme Toggle */}
      <div className="px-spacing-md flex h-12 w-full items-center justify-between pr-2">
        <HeaderLogo />
        <div className="flex items-center">
          <div id="header-actions" className="mr-2 flex items-center gap-2 empty:mr-0" />
          <NavLink
            to="/plugins"
            className={({ isActive }) =>
              `mr-2 flex items-center gap-1 px-2 py-1 text-sm text-on-surface no-underline hover:bg-surface-container-low ${isActive ? 'font-semibold' : ''}`
            }
          >
            <span className="material-symbols-outlined text-base">extension</span>
            Plugins
          </NavLink>
          <ThemeToggle />
        </div>
      </div>

      {/* Bottom row: Breadcrumb Navigation */}
      <BreadcrumbNav />
    </header>
  );
}
