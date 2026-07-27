// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getWorkflowGraph } from '../api/getWorkflowGraph';

export function useWorkflowGraph(sessionId: string, workflowId: string) {
  return useQuery({
    queryKey: ['sessions', sessionId, 'workflows', workflowId, 'graph'],
    queryFn: () => getWorkflowGraph(sessionId, workflowId),
    enabled: Boolean(sessionId && workflowId),
  });
}
