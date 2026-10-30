// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { aggregateStatus, groupNodes } from '../lib/groups';
import { toNodeUiStatus } from '../lib/statusMapper';
import type { TaskProgress, WorkflowGraph } from '../types/workflow';

/**
 * Which stages of the canvas are open, kept in the URL: `?open=lint,test` names them and
 * `?stages=all` shows every node ungrouped. Without `?open=`, the stages with a failed node open.
 */
export function useStageView(
  graph: WorkflowGraph | undefined,
  tasksByNode: Record<string, TaskProgress | undefined>
) {
  const [params, setParams] = useSearchParams();
  const stages = useMemo(() => groupNodes(graph?.nodes ?? []), [graph]);
  const requested = params.get('open');
  const grouped = params.get('stages') !== 'all';

  const open = useMemo(
    () =>
      new Set(
        requested !== null
          ? requested.split(',').filter(Boolean)
          : [...stages]
              .filter(
                ([, members]) =>
                  aggregateStatus(
                    members.map((m) =>
                      toNodeUiStatus(tasksByNode[m.nodeId]?.status)
                    )
                  ) === 'failed'
              )
              .map(([stage]) => stage)
      ),
    [requested, stages, tasksByNode]
  );

  const update = useCallback(
    (change: (next: URLSearchParams) => void) =>
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          change(next);
          return next;
        },
        { replace: true }
      ),
    [setParams]
  );

  const onToggle = useCallback(
    (stage: string) => {
      const next = new Set(open);
      if (!next.delete(stage)) next.add(stage);
      update((p) => p.set('open', [...next].join(',')));
    },
    [open, update]
  );

  const stageView = useMemo(
    () => (grouped && stages.size > 0 ? { open, onToggle } : undefined),
    [grouped, stages, open, onToggle]
  );

  const setGrouped = (value: boolean) =>
    update((p) => (value ? p.delete('stages') : p.set('stages', 'all')));

  return { stageView, hasStages: stages.size > 0, grouped, setGrouped };
}
