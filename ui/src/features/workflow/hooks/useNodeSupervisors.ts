// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createContext, useMemo } from 'react';
import { useOwnership } from '@/features/supervisors/hooks/useSupervisorTypes';

/** The supervisor behind each node, by node ID; nodes nobody supervises are left out. */
export const NodeSupervisors = createContext<ReadonlyMap<string, string>>(
  new Map()
);

export function useNodeSupervisors(
  sessionId: string,
  workflowId: string
): ReadonlyMap<string, string> {
  const { data } = useOwnership(sessionId, workflowId);
  return useMemo(
    () =>
      new Map(
        Object.entries(data?.nodes ?? {}).filter(([, id]) => id !== 'default')
      ),
    [data]
  );
}
