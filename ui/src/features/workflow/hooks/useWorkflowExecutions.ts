// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getWorkflowExecutions } from '../api/getWorkflowExecutions';

export function useWorkflowExecutions(sessionId: string, workflowId: string) {
  return useQuery({
    queryKey: ['sessions', sessionId, 'workflows', workflowId, 'executions'],
    queryFn: () => getWorkflowExecutions(sessionId, workflowId),
    enabled: Boolean(sessionId && workflowId),
  });
}
