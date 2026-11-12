// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMemo } from 'react';
import { useQueries } from '@tanstack/react-query';
import { getOwnership } from '../api/supervision';
import { parseScope } from '../lib/ownership';
import {
  MAX_WORKFLOWS,
  ownedNodeCount,
  workflowKey,
} from '../lib/supervisionGraph';
import { useAttachments } from './useSupervisorTypes';

/**
 * What a supervisor owns: its targets, and the nodes it owns in the whole workflows (read from the
 * ownership map for the first few: the map draws no more). Shared by the map and the Owns tile, so
 * the queries run once.
 */
export function useOwnedNodes(supervisorId: string) {
  const owned = useAttachments({ supervisor: supervisorId });
  const targets = useMemo(
    () => (owned.data ?? []).map((a) => parseScope(a.scope)),
    [owned.data]
  );
  const whole = useMemo(() => {
    const seen = new Map<string, (typeof targets)[number]>();
    for (const t of targets) {
      if (t.workflowId && t.sessionId && t.selectors.length === 0) {
        seen.set(workflowKey(t), t);
      }
    }
    return [...seen].slice(0, MAX_WORKFLOWS);
  }, [targets]);
  const ownership = useQueries({
    queries: whole.map(([, t]) => ({
      queryKey: ['supervision', 'ownership', t.sessionId, t.workflowId],
      queryFn: () =>
        getOwnership(t.sessionId as string, t.workflowId as string),
    })),
  });
  // a workflow whose map has not arrived stays out, so the count says it is partial
  const ownedNodes: Record<string, string[]> = {};
  whole.forEach(([key], i) => {
    const nodes = ownership[i]?.data?.nodes;
    if (nodes) {
      ownedNodes[key] = Object.entries(nodes)
        .filter(([, owner]) => owner === supervisorId)
        .map(([node]) => node);
    }
  });
  return {
    pending: owned.isPending,
    targets,
    ownedNodes,
    ...ownedNodeCount(targets, ownedNodes),
  };
}
