// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link, useLocation } from 'react-router';
import HeaderLogo from './HeaderLogo';
import IdentityChip from './IdentityChip';
import AccountMenu from './AccountMenu';
import ThemeToggle from './ThemeToggle';
import { openCommandPalette } from './CommandPalette';

/* `section` is the path prefix the item owns: Sessions is current on the list and on every
   session, workflow and editor page under /sessions. */
const NAV = [
  { to: '/', section: '/sessions', label: 'Sessions', icon: 'folder' },
  { to: '/runs', section: '/runs', label: 'Runs', icon: 'history' },
  { to: '/plugins', section: '/plugins', label: 'Plugins', icon: 'extension' },
  { to: '/control', section: '/control', label: 'Control', icon: 'hub' },
];

const isCurrent = (pathname: string, to: string, section: string) =>
  pathname === to || pathname === section || pathname.startsWith(`${section}/`);

/** Phones: the primary nav as a bottom tab bar, labels under the icons. */
export function MobileNav() {
  const { pathname } = useLocation();
  return (
    <nav
      aria-label="Primary"
      className="border-border bg-card fixed inset-x-0 bottom-0 z-50 grid h-16 grid-cols-4 border-t pb-[env(safe-area-inset-bottom)] sm:hidden"
    >
      {NAV.map(({ to, section, label, icon }) => {
        const active = isCurrent(pathname, to, section);
        return (
          <Link
            key={to}
            to={to}
            aria-current={active ? 'page' : undefined}
            className={`flex flex-col items-center justify-center gap-0.5 text-[11px] no-underline ${active ? 'text-primary font-semibold' : 'text-muted-foreground font-medium'}`}
          >
            <span aria-hidden="true" className="material-symbols-outlined">
              {icon}
            </span>
            {label}
          </Link>
        );
      })}
    </nav>
  );
}

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
      {/* As on the artboards: logo, sections; then search, account, theme */}
      <div className="px-spacing-md flex h-13 w-full items-center pr-2 sm:px-6">
        <HeaderLogo />
        <nav
          aria-label="Primary"
          className="ml-4 hidden items-center gap-0.5 sm:flex"
        >
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
                {label}
              </Link>
            );
          })}
        </nav>
        <div className="flex-1" />
        <button
          type="button"
          onClick={openCommandPalette}
          aria-keyshortcuts="Meta+K Control+K"
          aria-label="Search and jump to (⌘K)"
          className="bg-muted text-muted-foreground hover:text-foreground transition-standard text-body-md mr-2 flex h-8 items-center gap-2 rounded-full px-2.5 lg:w-75 lg:pr-2"
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-lg"
          >
            search
          </span>
          <span className="hidden flex-1 truncate text-left lg:inline">
            Jump to a session, workflow or run…
          </span>
          <kbd className="border-border bg-card hidden rounded border px-1.5 font-mono text-[11px] lg:inline">
            ⌘K
          </kbd>
        </button>
        <div className="hidden items-center sm:flex">
          <IdentityChip />
          <ThemeToggle />
        </div>
        <AccountMenu />
      </div>
    </header>
  );
}
