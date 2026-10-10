// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createBrowserRouter } from 'react-router';
import App from '@/App';
import ComingSoonPage from '@/components/layout/ComingSoonPage';
import { SessionListPage } from '@/features/sessions/components/SessionListPage';

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
        path: 'control',
        element: <ComingSoonPage title="Control Bus" />,
      },
      {
        path: 'history',
        element: <ComingSoonPage title="History" />,
      },
    ],
  },
]);

export default router;
