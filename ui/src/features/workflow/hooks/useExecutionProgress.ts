// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getWorkflowProgress } from '../api/getWorkflowProgress';
import { isTerminalExecStatus } from '../lib/statusMapper';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import type { WorkflowProgress } from '../types/workflow';

function isFinished(progress: WorkflowProgress | null): boolean {
  return progress != null && isTerminalExecStatus(progress.status) && progress.endTime != null;
}

/**
 * Track the progress of one execution: a query snapshot merged with live SSE updates.
 *
 * The SSE stream is only open while the execution is running; once a terminal update
 * arrives the stream closes and the executions list is invalidated so status chips refresh.
 */
export function useExecutionProgress(sessionId: string, executionId: string | null) {
  const queryClient = useQueryClient();
  const [liveProgress, setLiveProgress] = useState<WorkflowProgress | null>(null);
  const [liveExecutionId, setLiveExecutionId] = useState(executionId);

  // Render-phase reset: live progress belongs to one execution and clears when it changes
  if (liveExecutionId !== executionId) {
    setLiveExecutionId(executionId);
    setLiveProgress(null);
  }

  const query = useQuery({
    queryKey: ['workflow', sessionId, 'status', executionId],
    queryFn: () => getWorkflowProgress(sessionId, executionId as string),
    enabled: Boolean(sessionId && executionId),
  });

  const progress = liveProgress ?? query.data ?? null;
  const streamUrl =
    sessionId && executionId && progress && !isFinished(progress)
      ? `/api/workflow/${sessionId}/status/${executionId}/stream?includeHistory=true`
      : null;

  const onUpdate = useCallback(
    (update: WorkflowProgress) => {
      if (update.executionId !== executionId) {
        return;
      }
      setLiveProgress(update);
      if (isFinished(update)) {
        queryClient.invalidateQueries({
          queryKey: ['sessions', sessionId, 'workflows'],
        });
      }
    },
    [executionId, queryClient, sessionId]
  );

  const { status: streamStatus } = useEventSourceJson<WorkflowProgress>(streamUrl, onUpdate);

  return {
    progress,
    isLoading: query.isLoading,
    error: query.error,
    isStreaming: streamStatus === 'open' || streamStatus === 'connecting',
  };
}
