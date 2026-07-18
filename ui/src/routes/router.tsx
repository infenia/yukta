// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createBrowserRouter } from 'react-router';
import App from '@/App';
import ComingSoonPage from '@/components/layout/ComingSoonPage';

const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      {
        index: true,
        element: <div>Hello Yukta</div>,
      },
      {
        path: 'sessions/:sessionId',
        element: <ComingSoonPage title="Session Detail" />,
      },
      {
        path: 'sessions/:sessionId/workflow/:workflowId',
        element: <ComingSoonPage title="Workflow" />,
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
