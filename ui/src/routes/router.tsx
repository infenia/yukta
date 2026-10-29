// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createBrowserRouter, Navigate, useLocation } from 'react-router';
import App from '@/App';
import { SessionListPage } from '@/features/sessions/components/SessionListPage';

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
        element: <SessionListPage />,
      },
      {
        // Breadcrumbs link the "Sessions" crumb to /sessions; the list lives at the root.
        path: 'sessions',
        element: <Navigate to="/" replace />,
      },
      {
        path: 'sessions/new',
        // Builder pages stay out of the initial bundle
        lazy: async () => ({
          Component: (await import('@/features/builder/components/SessionFormPage')).SessionFormPage,
        }),
      },
      {
        path: 'sessions/import',
        lazy: async () => ({
          Component: (await import('@/features/builder/components/SessionImportPage')).SessionImportPage,
        }),
      },
      {
        path: 'sessions/:sessionId/edit',
        lazy: async () => ({
          Component: (await import('@/features/builder/components/SessionFormPage')).SessionFormPage,
        }),
      },
      {
        path: 'sessions/:sessionId',
        lazy: async () => ({
          Component: (await import('@/features/sessions/components/SessionDetailsPage'))
            .SessionDetailsPage,
        }),
      },
      {
        path: 'sessions/:sessionId/workflow/:workflowId',
        // Keeps React Flow and dagre out of the initial bundle
        lazy: async () => ({
          Component: (await import('@/features/workflow/components/WorkflowDetailsPage'))
            .WorkflowDetailsPage,
        }),
      },
      {
        path: 'sessions/:sessionId/workflow/:workflowId/edit',
        lazy: async () => ({
          Component: (await import('@/features/builder/components/WorkflowEditorPage'))
            .WorkflowEditorPage,
        }),
      },
      {
        path: 'plugins',
        lazy: async () => ({
          Component: (await import('@/features/plugins/components/PluginsPage')).PluginsPage,
        }),
      },
      {
        path: 'control',
        lazy: async () => ({
          Component: (await import('@/features/control/components/ControlPage')).ControlPage,
        }),
      },
      {
        path: 'runs',
        lazy: async () => ({
          Component: (await import('@/features/runs/components/RunsPage')).RunsPage,
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
