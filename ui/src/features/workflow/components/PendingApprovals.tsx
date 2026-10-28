// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { InlineError } from '@/components/common/InlineError';
import { controlErrorMessage } from '../hooks/useExecutionControl';
import {
  getPendingApprovals,
  sendDecision,
  type PendingApproval,
} from '../api/approvals';

interface Props {
  sessionId: string;
  workflowId: string;
}

/** Steps of this workflow that wait for a person to approve or reject. */
export function PendingApprovals({ sessionId, workflowId }: Props) {
  const queryClient = useQueryClient();
  const key = ['approvals', sessionId];
  const approvals = useQuery({
    queryKey: key,
    queryFn: () => getPendingApprovals(sessionId),
    refetchInterval: 5000,
  });
  const decide = useMutation({
    mutationFn: ({
      approval,
      decision,
    }: {
      approval: PendingApproval;
      decision: 'approve' | 'reject';
    }) => sendDecision(approval, decision),
    onSettled: () => queryClient.invalidateQueries({ queryKey: key }),
  });

  const mine = (approvals.data ?? []).filter(
    (approval) => approval.workflowId === workflowId
  );
  if (mine.length === 0 && !decide.error) {
    return null;
  }

  return (
    <section
      aria-label="Pending approvals"
      className="flex flex-col gap-2 border-b border-outline-variant bg-surface-container-low px-6 py-3"
    >
      {decide.error && (
        <InlineError message={controlErrorMessage(decide.error)} />
      )}
      {mine.map((approval) => (
        <div
          key={`${approval.executionId}-${approval.nodeId}-${approval.name}`}
          className="flex items-center gap-3"
        >
          <span className="text-body-md text-on-surface">
            <span className="font-mono">{approval.nodeId}</span> waits for “
            {approval.name}” (run{' '}
            <span className="font-mono">
              {approval.executionId.slice(0, 8)}
            </span>
            )
          </span>
          <Button
            size="sm"
            disabled={decide.isPending}
            onClick={() => decide.mutate({ approval, decision: 'approve' })}
          >
            Approve
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={decide.isPending}
            onClick={() => decide.mutate({ approval, decision: 'reject' })}
          >
            Reject
          </Button>
        </div>
      ))}
    </section>
  );
}
