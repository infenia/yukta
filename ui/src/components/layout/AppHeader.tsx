// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { NavLink } from 'react-router';
import HeaderLogo from './HeaderLogo';
import BreadcrumbNav from './BreadcrumbNav';
import ThemeToggle from './ThemeToggle';

const NAV = [
  { to: '/', label: 'Sessions', icon: 'folder', end: true },
  { to: '/plugins', label: 'Plugins', icon: 'extension', end: false },
  { to: '/history', label: 'History', icon: 'history', end: false },
  { to: '/control', label: 'Control', icon: 'hub', end: false },
];

export default function AppHeader() {
  return (
    <header className="border-outline-variant bg-surface fixed inset-x-0 top-0 z-50 border-b">
      {/* Top row: Logo + page actions + Theme Toggle */}
      <div className="px-spacing-md flex h-12 w-full items-center justify-between pr-2 sm:px-6">
        <HeaderLogo />
        <div className="flex items-center">
          <div
            id="header-actions"
            className="mr-2 flex items-center gap-2 empty:mr-0"
          />
          <nav aria-label="Primary" className="mr-2 flex items-center">
            {NAV.map(({ to, label, icon, end }) => (
              <NavLink
                key={to}
                to={to}
                end={end}
                className={({ isActive }) =>
                  `transition-standard hover:bg-surface-container-low flex items-center gap-1 border-b-2 px-2 py-1 text-sm no-underline ${isActive ? 'border-primary text-primary font-semibold' : 'text-on-surface border-transparent'}`
                }
              >
                <span className="material-symbols-outlined text-base">
                  {icon}
                </span>
                {label}
              </NavLink>
            ))}
          </nav>
          <ThemeToggle />
        </div>
      </div>

      {/* Bottom row: Breadcrumb Navigation */}
      <BreadcrumbNav />
    </header>
  );
}
