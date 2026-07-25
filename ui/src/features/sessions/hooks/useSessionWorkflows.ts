// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getSessionWorkflows } from '../api/getSessionWorkflows';

export function useSessionWorkflows(sessionId: string) {
  return useQuery({
    queryKey: ['sessions', sessionId, 'workflows'],
    queryFn: async () => {
      const data = await getSessionWorkflows(sessionId);
      return data.workflows;
    },
    enabled: Boolean(sessionId),
  });
}
