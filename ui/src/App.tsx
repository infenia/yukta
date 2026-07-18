// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Outlet } from 'react-router';
import AppHeader from '@/components/layout/AppHeader';
import AppFooter from '@/components/layout/AppFooter';

export default function App() {
  return (
    <div className="min-h-screen flex flex-col bg-background text-on-surface">
      <AppHeader />
      <main className="flex-1 flex flex-col">
        <Outlet />
      </main>
      <AppFooter />
    </div>
  );
}
