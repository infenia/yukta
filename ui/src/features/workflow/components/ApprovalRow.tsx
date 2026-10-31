// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  useMutation,
  useQueryClient,
  type QueryKey,
} from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { RequirePermission } from '@/components/common/RequirePermission';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { relativeTime } from '@/features/control/components/WorkersPanel.helpers';
import { sendDecision, type PendingApproval } from '../api/approvals';
import { controlErrorMessage } from '../hooks/useExecutionControl';

/** How long a step has waited, as "3 minutes ago". */
export const waitingSince = (since: string) =>
  relativeTime(since).replace(/^in /, '');

/**
 * One waiting step with its own Approve and Reject: Approve at once, Reject after a confirm with
 * an optional reason. Each row tracks its own pending state, and a repeated decision (the server
 * answers 409 within seconds of the first) says it was already answered. `label` is the sentence
 * that names the step; `queryKey` is the list to refresh afterwards.
 */
export function ApprovalRow({
  approval,
  label,
  queryKey,
}: {
  approval: PendingApproval;
  label: ReactNode;
  queryKey: QueryKey;
}) {
  const queryClient = useQueryClient();
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const decide = useMutation({
    mutationFn: (decision: 'approve' | 'reject') =>
      sendDecision(
        approval,
        decision,
        decision === 'reject' ? reason : undefined
      ),
    onSettled: () => queryClient.invalidateQueries({ queryKey }),
  });
  const alreadyAnswered =
    (decide.error as { status?: number } | null)?.status === 409;
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-body-md text-on-surface min-w-0 flex-1">
        {label}{' '}
        <span className="text-on-surface-variant">
          since {waitingSince(approval.since)}
        </span>
      </span>
      <RequirePermission perm="controlRuns">
        <Button
          size="sm"
          disabled={decide.isPending}
          onClick={() => decide.mutate('approve')}
        >
          Approve
        </Button>
      </RequirePermission>
      <RequirePermission perm="controlRuns">
        <Button
          size="sm"
          variant="outline"
          disabled={decide.isPending}
          onClick={() => setRejecting(true)}
        >
          Reject
        </Button>
      </RequirePermission>
      {decide.error && (
        <div className="basis-full">
          <InlineError
            message={
              alreadyAnswered
                ? 'Already answered.'
                : controlErrorMessage(decide.error)
            }
          />
        </div>
      )}
      {rejecting && (
        <Modal
          title="Reject this approval?"
          onClose={() => setRejecting(false)}
        >
          <label
            htmlFor="reject-reason"
            className="text-label-md text-on-surface"
          >
            Reason (optional)
          </label>
          <Input
            id="reject-reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
          <div className="mt-4 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setRejecting(false)}>
              Cancel
            </Button>
            <Button
              onClick={() => {
                setRejecting(false);
                decide.mutate('reject');
              }}
            >
              Reject
            </Button>
          </div>
        </Modal>
      )}
    </div>
  );
}
