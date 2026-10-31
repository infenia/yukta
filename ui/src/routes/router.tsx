// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  createBrowserRouter,
  Navigate,
  RouterProvider,
  useLocation,
} from 'react-router';
import App from '@/App';
import { SessionListPage } from '@/features/sessions/components/SessionListPage';
import { OverviewPage } from '@/features/overview/OverviewPage';

/** The session list moved from / to /sessions; its query (?q=x) is kept. */
export function HomeRoute() {
  const { search } = useLocation();
  return search ? (
    <Navigate to={{ pathname: '/sessions', search }} replace />
  ) : (
    <OverviewPage />
  );
}

/** /history became /runs; the query (?session=x) is kept. */
export function HistoryRedirect() {
  const { search } = useLocation();
  return <Navigate to={{ pathname: '/runs', search }} replace />;
}

const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      {
        index: true,
        element: <HomeRoute />,
      },
      {
        path: 'sessions',
        element: <SessionListPage />,
      },
      {
        path: 'sessions/new',
        // Builder pages stay out of the initial bundle
        lazy: async () => ({
          Component: (
            await import('@/features/builder/components/SessionFormPage')
          ).SessionFormPage,
        }),
      },
      {
        path: 'sessions/import',
        lazy: async () => ({
          Component: (
            await import('@/features/builder/components/SessionImportPage')
          ).SessionImportPage,
        }),
      },
      {
        path: 'sessions/:sessionId/edit',
        lazy: async () => ({
          Component: (
            await import('@/features/builder/components/SessionFormPage')
          ).SessionFormPage,
        }),
      },
      {
        path: 'sessions/:sessionId',
        lazy: async () => ({
          Component: (
            await import('@/features/sessions/components/SessionDetailsPage')
          ).SessionDetailsPage,
        }),
      },
      {
        path: 'sessions/:sessionId/workflow/:workflowId',
        // Keeps React Flow and dagre out of the initial bundle
        lazy: async () => ({
          Component: (
            await import('@/features/workflow/components/WorkflowDetailsPage')
          ).WorkflowDetailsPage,
        }),
      },
      {
        path: 'sessions/:sessionId/workflow/:workflowId/edit',
        lazy: async () => ({
          Component: (
            await import('@/features/builder/components/WorkflowEditorPage')
          ).WorkflowEditorPage,
        }),
      },
      {
        path: 'plugins',
        lazy: async () => ({
          Component: (await import('@/features/plugins/components/PluginsPage'))
            .PluginsPage,
        }),
      },
      {
        path: 'control',
        lazy: async () => ({
          Component: (await import('@/features/control/components/ControlPage'))
            .ControlPage,
        }),
      },
      {
        path: 'runs',
        lazy: async () => ({
          Component: (await import('@/features/runs/components/RunsPage'))
            .RunsPage,
        }),
      },
      {
        // the former History page: old links keep working, with their query
        path: 'history',
        element: <HistoryRedirect />,
      },
    ],
  },
]);

export default router;

/** The app's router, rendered once by main.tsx. */
export function AppRouter() {
  return <RouterProvider router={router} />;
}
