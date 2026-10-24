// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link, useLocation } from 'react-router';
import HeaderLogo from './HeaderLogo';
import BreadcrumbNav from './BreadcrumbNav';
import ThemeToggle from './ThemeToggle';

/* `section` is the path prefix the item owns: Sessions is current on the list and on every
   session, workflow and editor page under /sessions. */
const NAV = [
  { to: '/', section: '/sessions', label: 'Sessions', icon: 'folder' },
  { to: '/plugins', section: '/plugins', label: 'Plugins', icon: 'extension' },
  { to: '/history', section: '/history', label: 'History', icon: 'history' },
  { to: '/control', section: '/control', label: 'Control', icon: 'hub' },
];

const isCurrent = (pathname: string, to: string, section: string) =>
  pathname === to || pathname === section || pathname.startsWith(`${section}/`);

export default function AppHeader() {
  const { pathname } = useLocation();
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
            {NAV.map(({ to, section, label, icon }) => {
              const active = isCurrent(pathname, to, section);
              return (
                <Link
                  key={to}
                  to={to}
                  aria-current={active ? 'page' : undefined}
                  className={`transition-standard text-body-md flex items-center gap-1.5 rounded-lg px-2 py-1.5 no-underline sm:px-2.5 ${active ? 'bg-accent text-accent-foreground font-semibold' : 'text-muted-foreground hover:bg-muted hover:text-foreground font-medium'}`}
                >
                  <span
                    aria-hidden="true"
                    className="material-symbols-outlined text-base"
                  >
                    {icon}
                  </span>
                  {/* Icons only on phones, so the header fits; the label stays the link's name */}
                  <span className="sr-only sm:not-sr-only">{label}</span>
                </Link>
              );
            })}
          </nav>
          <ThemeToggle />
        </div>
      </div>

      {/* Bottom row: Breadcrumb Navigation */}
      <BreadcrumbNav />
    </header>
  );
}
