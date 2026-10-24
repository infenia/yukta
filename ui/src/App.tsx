// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Outlet } from 'react-router';
import AppHeader from '@/components/layout/AppHeader';
import ApiKeyPrompt from '@/components/common/ApiKeyPrompt';
import AppFooter from '@/components/layout/AppFooter';
import { BreadcrumbOverridesProvider } from '@/lib/breadcrumbOverrides';

export default function App() {
  return (
    <BreadcrumbOverridesProvider>
      <div className="bg-background text-on-surface flex min-h-screen flex-col">
        <AppHeader />
        <ApiKeyPrompt />
        <main className="mt-header flex w-full flex-1 flex-col">
          <Outlet />
        </main>
        <AppFooter />
      </div>
    </BreadcrumbOverridesProvider>
  );
}
