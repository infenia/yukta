// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useRef } from 'react';
import { Outlet, useLocation } from 'react-router';
import AppHeader from '@/components/layout/AppHeader';
import ApiKeyPrompt from '@/components/common/ApiKeyPrompt';
import AppFooter from '@/components/layout/AppFooter';
import { BreadcrumbOverridesProvider } from '@/lib/breadcrumbOverrides';
import { usePageTransition } from '@/lib/usePageTransition';

export default function App() {
  const mainRef = useRef<HTMLElement>(null);
  const { pathname } = useLocation();
  usePageTransition(mainRef, pathname);

  return (
    <BreadcrumbOverridesProvider>
      <div className="bg-background text-on-surface flex min-h-screen flex-col">
        <AppHeader />
        <ApiKeyPrompt />
        <main ref={mainRef} className="mt-header flex w-full flex-1 flex-col">
          <Outlet />
        </main>
        <AppFooter />
      </div>
    </BreadcrumbOverridesProvider>
  );
}
