// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { createContext, useMemo } from 'react';
import { getExecutionTree } from '../api/getWorkflowProgress';
import type { WorkflowGraph } from '../types/workflow';

/** Where each sub-workflow node's "Open" goes, by node ID; empty outside the run view. */
export const SubWorkflowLinks = createContext<ReadonlyMap<string, string>>(
  new Map()
);

/** How often a live run's sub-workflow runs are looked up again. */
const LIVE_POLL_MS = 5000;

/**
 * The page of the workflow each SUB_WORKFLOW node runs, opened on the run it started in this
 * execution when there is one (the newest, if it ran more than once).
 */
export function useSubWorkflowLinks(
  sessionId: string,
  graph: WorkflowGraph | undefined,
  executionId: string | null,
  live: boolean
): ReadonlyMap<string, string> {
  const subNodes = useMemo(
    () => (graph?.nodes ?? []).filter((node) => node.subWorkflowId),
    [graph]
  );
  const tree = useQuery({
    queryKey: ['workflow', sessionId, 'tree', executionId],
    queryFn: () => getExecutionTree(executionId as string),
    enabled: executionId !== null && subNodes.length > 0,
    refetchInterval: live ? LIVE_POLL_MS : false,
  });

  return useMemo(() => {
    const children = (tree.data ?? []).filter(
      (run) => run.parentExecutionId === executionId
    );
    // ponytail: two nodes that run the same workflow share its newest run; record the parent
    // node on the child run if that matters
    return new Map(
      subNodes.map((node) => {
        const workflowId = node.subWorkflowId as string;
        const runs = children.filter((run) => run.workflowId === workflowId);
        const child = runs[runs.length - 1];
        const page = `/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflowId)}`;
        return [
          node.nodeId,
          child
            ? `${page}?exec=${encodeURIComponent(child.executionId)}`
            : page,
        ];
      })
    );
  }, [tree.data, subNodes, executionId, sessionId]);
}
