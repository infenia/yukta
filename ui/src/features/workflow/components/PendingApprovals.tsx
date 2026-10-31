// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getPendingApprovals } from '../api/approvals';
import { ApprovalRow } from './ApprovalRow';

interface Props {
  sessionId: string;
  workflowId: string;
  /** The run on screen is going: only then can a step start to wait, so only then poll. */
  live?: boolean;
}

/** Steps of this workflow that wait for a person to approve or reject. */
export function PendingApprovals({
  sessionId,
  workflowId,
  live = true,
}: Props) {
  const key = ['approvals', sessionId];
  const approvals = useQuery({
    queryKey: key,
    queryFn: () => getPendingApprovals(sessionId),
    refetchInterval: live ? 5000 : false,
  });

  const mine = (approvals.data ?? []).filter(
    (approval) => approval.workflowId === workflowId
  );
  // The live region stays in the page so that the strip is announced when it appears
  if (mine.length === 0) {
    return <div data-testid="approvals-live" aria-live="polite" />;
  }

  return (
    <section
      aria-label="Pending approvals"
      aria-live="polite"
      className="flex flex-col gap-2 border-b border-outline-variant bg-surface-container-low px-6 py-3"
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
