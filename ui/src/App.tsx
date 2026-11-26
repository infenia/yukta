// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { Outlet, useLocation } from 'react-router';
import AppHeader, { MobileNav, Sidebar } from '@/components/layout/AppHeader';
import { CommandPalette } from '@/components/layout/CommandPalette';
import ApiKeyPrompt from '@/components/common/ApiKeyPrompt';
import AppFooter from '@/components/layout/AppFooter';
import { loadMe } from '@/features/auth/me';
import { useApiKeyRequired } from '@/lib/apiKey';
import { usePageTransition } from '@/lib/usePageTransition';
import { NotificationsProvider } from '@/features/inbox/NotificationsProvider';
import { DesktopNotifier } from '@/features/inbox/DesktopNotifier';
import { Toaster } from '@/features/inbox/Toaster';

export default function App() {
  const mainRef = useRef<HTMLElement>(null);
  const { pathname } = useLocation();
  usePageTransition(mainRef, pathname);
  const keyRequired = useApiKeyRequired();
  // The shell (and every query and stream in it) mounts only once /api/me has answered, so a
  // server that wants a key gets one request, not a burst of them that trips its failure limit.
  const [checked, setChecked] = useState(false);
  useEffect(() => {
    // flushSync: the shell's effects (Ctrl+K, Ctrl+J listeners) run in the same commit, so a
    // shortcut works as soon as the shell shows
    void loadMe().finally(() => flushSync(() => setChecked(true)));
  }, []);

  // The run page fills the screen with the logs terminal docked at its bottom; a footer below
  // would make the document scroll. The editor (.../edit) and every other page keep it.
  const showFooter = !/^\/sessions\/[^/]+\/workflow\/[^/]+\/?$/.test(pathname);

  if (keyRequired) {
    return <ApiKeyPrompt />;
  }
  if (!checked) {
    return <div className="bg-background min-h-screen" />;
  }

  return (
    <NotificationsProvider>
      <div className="bg-background text-on-surface flex min-h-screen flex-col pb-16 sm:pb-0 sm:pl-16">
        <AppHeader />
        <Sidebar />
        <main
          ref={mainRef}
          className="mt-header relative isolate flex w-full flex-1 flex-col"
        >
          <Outlet />
        </main>
        {showFooter && <AppFooter />}
        <MobileNav />
        <CommandPalette />
        <Toaster />
        <DesktopNotifier />
      </div>
    </NotificationsProvider>
  );
}
