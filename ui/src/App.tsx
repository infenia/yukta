// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef } from 'react';
import { Outlet, useLocation } from 'react-router';
import AppHeader, { MobileNav } from '@/components/layout/AppHeader';
import { CommandPalette } from '@/components/layout/CommandPalette';
import ApiKeyPrompt from '@/components/common/ApiKeyPrompt';
import AppFooter from '@/components/layout/AppFooter';
import { loadMe } from '@/features/auth/me';
import { usePageTransition } from '@/lib/usePageTransition';

export default function App() {
  const mainRef = useRef<HTMLElement>(null);
  const { pathname } = useLocation();
  usePageTransition(mainRef, pathname);
  useEffect(() => {
    void loadMe();
  }, []);

  // The run page fills the screen with the logs terminal docked at its bottom; a footer below
  // would make the document scroll. The editor (.../edit) and every other page keep it.
  const showFooter = !/^\/sessions\/[^/]+\/workflow\/[^/]+\/?$/.test(pathname);

  return (
    <div className="bg-background text-on-surface flex min-h-screen flex-col pb-16 sm:pb-0">
      <AppHeader />
      <ApiKeyPrompt />
      <main
        ref={mainRef}
        className="mt-header relative isolate flex w-full flex-1 flex-col"
      >
        <Outlet />
      </main>
      {showFooter && <AppFooter />}
      <MobileNav />
      <CommandPalette />
    </div>
  );
}
