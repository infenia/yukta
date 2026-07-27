// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';
import type { ExecutionSummary } from '../types/workflow';

/**
 * Select which execution the page shows, persisted in the `?exec=` search param.
 *
 * A valid `?exec=` wins; otherwise the latest execution is used without writing the
 * param. An empty history yields null (the never-run state).
 */
export function useExecutionSelection(executions: ExecutionSummary[]) {
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get('exec');

  const selectedExecutionId = useMemo(() => {
    if (requested && executions.some((execution) => execution.executionId === requested)) {
      return requested;
    }
    return executions[0]?.executionId ?? null;
  }, [requested, executions]);

  const selectExecution = useCallback(
    (executionId: string) => {
      setSearchParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          next.set('exec', executionId);
          return next;
        },
        { replace: true }
      );
    },
    [setSearchParams]
  );

  return { selectedExecutionId, selectExecution };
}
