// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link, useLocation } from 'react-router';
import HeaderLogo from './HeaderLogo';
import IdentityChip from './IdentityChip';
import AccountMenu from './AccountMenu';
import ThemeToggle from './ThemeToggle';
import { openCommandPalette } from './CommandPalette.helpers';

/* `section` is the path prefix the item owns: Sessions is current on the list and on every
   session, workflow and editor page under /sessions. */
const NAV = [
  { to: '/', section: '/overview', label: 'Overview', icon: 'dashboard' },
  { to: '/sessions', section: '/sessions', label: 'Sessions', icon: 'folder' },
  { to: '/runs', section: '/runs', label: 'Runs', icon: 'history' },
  { to: '/plugins', section: '/plugins', label: 'Plugins', icon: 'extension' },
  { to: '/control', section: '/control', label: 'Control', icon: 'hub' },
];

const HEADER_NAV = NAV.slice(0, 1);
const SIDE_NAV = NAV.slice(1);

const isCurrent = (pathname: string, to: string, section: string) =>
  pathname === to || pathname === section || pathname.startsWith(`${section}/`);

/** Phones: the primary nav as a bottom tab bar, labels under the icons. */
export function MobileNav() {
  const { pathname } = useLocation();
  return (
    <nav
      aria-label="Primary"
      className="fixed inset-x-0 bottom-0 z-50 grid h-16 grid-cols-5 border-t border-border bg-card pb-[env(safe-area-inset-bottom)] sm:hidden"
    >
      {NAV.map(({ to, section, label, icon }) => {
        const active = isCurrent(pathname, to, section);
        return (
          <Link
            key={to}
            to={to}
            aria-current={active ? 'page' : undefined}
            className={`flex flex-col items-center justify-center gap-0.5 text-[11px] no-underline ${active ? 'font-semibold text-primary' : 'font-medium text-muted-foreground'}`}
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

/** Desktop: a 64px icon rail under the header; the label shows on hover or keyboard focus. */
export function Sidebar() {
  const { pathname } = useLocation();
  return (
    <nav
      aria-label="Primary"
      className="border-border bg-background top-header fixed bottom-0 left-0 z-40 hidden w-16 flex-col items-center gap-1 border-r py-3 sm:flex"
    >
      {SIDE_NAV.map(({ to, section, label, icon }) => {
        const active = isCurrent(pathname, to, section);
        return (
          <Link
            key={to}
            to={to}
            aria-current={active ? 'page' : undefined}
            className={`group transition-standard relative flex size-10 items-center justify-center rounded-lg no-underline ${active ? 'bg-accent text-accent-foreground' : 'text-muted-foreground hover:bg-muted hover:text-foreground'}`}
          >
            <span aria-hidden="true" className="material-symbols-outlined">
              {icon}
            </span>
            <span className="bg-foreground text-background text-label-sm pointer-events-none absolute left-full ml-2 rounded-md px-2 py-1 whitespace-nowrap opacity-0 group-hover:opacity-100 group-focus-visible:opacity-100">
              {label}
            </span>
          </Link>
        );
      })}
    </nav>
  );
}

export default function AppHeader() {
  const { pathname } = useLocation();
  return (
    <header className="fixed inset-x-0 top-0 z-50 border-b border-border bg-background/80 backdrop-blur-md">
      {/* The logo as a 2px line: the blue flow ending in its orange dot (as on the docs site) */}
      <div
        aria-hidden="true"
        data-testid="brand-line"
        className="pointer-events-none absolute inset-x-0 top-0 h-0.5 bg-[linear-gradient(90deg,var(--primary)_0_94%,var(--attention)_94%_100%)]"
      />
      {/* As on the artboards: logo, sections; then search, account, theme */}
      <div className="flex h-13 w-full items-center px-spacing-md pr-2 sm:px-6">
        <HeaderLogo />
        <nav
          aria-label="Home"
          className="ml-4 hidden items-center gap-0.5 sm:flex"
        >
          {HEADER_NAV.map(({ to, section, label, icon }) => {
            const active = isCurrent(pathname, to, section);
            return (
              <Link
                key={to}
                to={to}
                aria-current={active ? 'page' : undefined}
                className={`flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-body-md no-underline transition-standard sm:px-2.5 ${active ? 'bg-accent font-semibold text-accent-foreground' : 'font-medium text-muted-foreground hover:bg-muted hover:text-foreground'}`}
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
          className="mr-2 flex h-8 items-center gap-2 rounded-full bg-muted px-2.5 text-body-md text-muted-foreground transition-standard hover:text-foreground lg:w-75 lg:pr-2"
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
          <kbd className="hidden rounded border border-border bg-card px-1.5 font-mono text-[11px] lg:inline">
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
