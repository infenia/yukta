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
    <header className="border-border bg-background/80 fixed inset-x-0 top-0 z-50 border-b backdrop-blur-md">
      {/* The logo as a 2px line: the blue flow ending in its orange dot (as on the docs site) */}
      <div
        aria-hidden="true"
        data-testid="brand-line"
        className="pointer-events-none absolute inset-x-0 top-0 h-0.5 bg-[linear-gradient(90deg,var(--primary)_0_94%,var(--attention)_94%_100%)]"
      />
      {/* Top row: Logo + page actions + Theme Toggle */}
      <div className="px-spacing-md flex h-12 w-full items-center justify-between pr-2 sm:px-6">
        <HeaderLogo />
        <div className="flex items-center">
          <div
            id="header-actions"
            className="mr-2 flex items-center gap-2 empty:mr-0"
          />
          <nav aria-label="Primary" className="mr-1 flex items-center gap-1">
            {NAV.map(({ to, label, icon, end }) => (
              <NavLink
                key={to}
                to={to}
                end={end}
                className={({ isActive }) =>
                  `transition-standard text-body-md flex items-center gap-1.5 rounded-lg px-2 py-1.5 no-underline sm:px-2.5 ${isActive ? 'bg-accent text-accent-foreground font-semibold' : 'text-muted-foreground hover:bg-muted hover:text-foreground font-medium'}`
                }
              >
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-base"
                >
                  {icon}
                </span>
                {/* Icons only on phones, so the header fits; the label stays the link's name */}
                <span className="sr-only sm:not-sr-only">{label}</span>
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
