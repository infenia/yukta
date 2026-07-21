// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Outlet } from 'react-router';
import AppHeader from '@/components/layout/AppHeader';
import AppFooter from '@/components/layout/AppFooter';

export default function App() {
  return (
    <div className="flex min-h-screen flex-col bg-background text-on-surface">
      <AppHeader />
      <main className="mt-20 flex flex-1 flex-col">
        <Outlet />
      </main>
      <AppFooter />
    </div>
  );
}
