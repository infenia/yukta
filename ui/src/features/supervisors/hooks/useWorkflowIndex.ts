// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getSupervisionSummary } from '../api/supervision';
import { workflowIndex } from '../lib/workflowIndex';
import { useAttachments, useSupervisorDecisions } from './useSupervisorTypes';

/**
 * The workflows and who controls them, from the assignments, the workflows nobody owns and the
 * newest decisions. The decisions only add a hint, so they never hold the list back.
 */
export function useWorkflowIndex() {
  const attachments = useAttachments();
  const summary = useQuery({
    queryKey: ['supervision', 'summary'],
    queryFn: getSupervisionSummary,
  });
  const decisions = useSupervisorDecisions();
  const ready = attachments.data && summary.data;
  return {
    isPending: attachments.isPending || summary.isPending,
    failed: !ready && !(attachments.isPending || summary.isPending),
    error: attachments.error ?? summary.error,
    refetch: () => {
      void attachments.refetch();
      void summary.refetch();
    },
    attachments: attachments.data ?? [],
    unowned: summary.data?.unowned ?? 0,
    ...(ready
      ? workflowIndex(attachments.data, summary.data, decisions.data ?? [])
      : { rows: [], across: [] }),
  };
}
