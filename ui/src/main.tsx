// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router';
import router from './routes/router';
import queryClient from './lib/queryClient';
import { getTheme } from './lib/theme';
import './index.css';

// Initialize theme before rendering
const theme = getTheme();
const isDark =
  theme === 'dark' ||
  (theme === 'system' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches);

if (isDark) {
  document.documentElement.classList.add('dark');
} else {
  document.documentElement.classList.remove('dark');
}

const rootElement = document.getElementById('root');
if (rootElement) {
  ReactDOM.createRoot(rootElement).render(
    <React.StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </React.StrictMode>
  );
}
