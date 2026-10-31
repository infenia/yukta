// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getPendingApprovals } from '../api/approvals';
import { ApprovalRow } from './ApprovalRow';

interface Props {
  sessionId: string;
  workflowId: string;
}

/** Steps of this workflow that wait for a person to approve or reject. */
export function PendingApprovals({ sessionId, workflowId }: Props) {
  const key = ['approvals', sessionId];
  const approvals = useQuery({
    queryKey: key,
    queryFn: () => getPendingApprovals(sessionId),
    refetchInterval: 5000,
  });
  const mine = (approvals.data ?? []).filter(
    (approval) => approval.workflowId === workflowId
  );
  if (mine.length === 0) {
    return null;
  }

  return (
    <section
      aria-label="Pending approvals"
      className="border-outline-variant bg-surface-container-low flex flex-col gap-2 border-b px-6 py-3"
    >
      {mine.map((approval) => (
        <ApprovalRow
          key={`${approval.executionId}-${approval.nodeId}-${approval.name}`}
          approval={approval}
          queryKey={key}
          label={
            <>
              <span className="font-mono">{approval.nodeId}</span> waits for “
              {approval.name}” (run{' '}
              <span className="font-mono">
                {approval.executionId.slice(0, 8)}
              </span>
              )
            </>
          }
        />
      ))}
    </section>
  );
}
