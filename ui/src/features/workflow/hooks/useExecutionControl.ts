// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/lib/apiClient';
import {
  clearBreakpoint,
  changePriority,
  nodeAction,
  recoverExecution,
  restartFromNode,
  runAction,
  setBreakpoint,
  startWorkflow,
  stopWorkflow,
  type NodeAction,
  type RunAction,
  type StopMode,
} from '../api/executionControl';

/** Human-readable message for a failed control call. */
export function controlErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 429) {
    return 'The scheduler is at its limit. Try again shortly.';
  }
  return error instanceof Error ? error.message : 'Request failed';
}

/**
 * Mutations for starting and controlling executions. Each refreshes the status, run history
 * and workflow-list queries so the page reflects the new state without a manual reload.
 */
export function useExecutionControl(sessionId: string, workflowId: string) {
  const queryClient = useQueryClient();
  const onSuccess = () =>
    Promise.all([
      queryClient.invalidateQueries({
        queryKey: ['workflow', sessionId, 'status'],
      }),
      queryClient.invalidateQueries({
        queryKey: ['sessions', sessionId, 'workflows'],
      }),
    ]);

  return {
    start: useMutation({
      onSuccess,
      // The caller keeps one key per Run dialog, so a retry or double click is not a second run.
      mutationFn: ({
        payload,
        idempotencyKey,
      }: {
        payload: Record<string, unknown>;
        idempotencyKey: string;
      }) => startWorkflow(sessionId, workflowId, payload, idempotencyKey),
    }),
    runAction: useMutation({
      onSuccess,
      mutationFn: ({
        executionId,
        action,
      }: {
        executionId: string;
        action: RunAction;
      }) => runAction(executionId, action),
    }),
    priority: useMutation({
      onSuccess,
      mutationFn: (vars: { executionId: string; priority: number }) =>
        changePriority(vars.executionId, vars.priority),
    }),
    recover: useMutation({
      onSuccess: () =>
        Promise.all([
          onSuccess(),
          queryClient.invalidateQueries({
            queryKey: [
              'sessions',
              sessionId,
              'workflows',
              workflowId,
              'executions',
            ],
          }),
        ]),
      mutationFn: (executionId: string) => recoverExecution(executionId),
    }),
    stopAll: useMutation({
      onSuccess,
      mutationFn: (mode: StopMode) => stopWorkflow(sessionId, workflowId, mode),
    }),
    restartFromNode: useMutation({
      onSuccess,
      mutationFn: ({
        executionId,
        nodeId,
      }: {
        executionId: string;
        nodeId: string;
      }) => restartFromNode(executionId, nodeId),
    }),
    setBreakpoint: useMutation({
      onSuccess,
      mutationFn: (vars: {
        executionId: string;
        nodeId: string;
        field: string;
        equals: string;
      }) =>
        setBreakpoint(vars.executionId, vars.nodeId, vars.field, vars.equals),
    }),
    clearBreakpoint: useMutation({
      onSuccess,
      mutationFn: (vars: { executionId: string; nodeId: string }) =>
        clearBreakpoint(vars.executionId, vars.nodeId),
    }),
    nodeAction: useMutation({
      onSuccess,
      mutationFn: (vars: {
        executionId: string;
        nodeId: string;
        action: NodeAction;
      }) => nodeAction(vars.executionId, vars.nodeId, vars.action),
    }),
  };
}
