// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createContext, useMemo } from 'react';
import { useOwnership } from '@/features/supervisors/hooks/useSupervisorTypes';

/** The owner of each node, by node ID: a supervisor id or `default`; empty until it is known. */
export const NodeSupervisors = createContext<ReadonlyMap<string, string>>(
  new Map()
);

/** Opens a node's supervisor tab; absent where there is no drawer to open (the editor). */
export const NodeOwnerOpener = createContext<((nodeId: string) => void) | null>(
  null
);

export function useNodeSupervisors(
  sessionId: string,
  workflowId: string
): ReadonlyMap<string, string> {
  const { data } = useOwnership(sessionId, workflowId);
  return useMemo(() => new Map(Object.entries(data?.nodes ?? {})), [data]);
}
