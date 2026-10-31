// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { ConfirmDeleteDialog } from '@/components/common/ConfirmDeleteDialog';
import { deleteSession, deleteWorkflow } from '../api/deleteSession';

/** Confirm and delete a session; afterwards the session queries are refreshed and the list opens. */
export function DeleteSessionDialog({
  sessionId,
  onClose,
}: {
  sessionId: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return (
    <ConfirmDeleteDialog
      title={`Delete session ${sessionId}`}
      id={sessionId}
      removes="the session, all its workflows and their saved versions"
      keeps="the run history and logs, which age out with the log retention"
      remove={(force) => deleteSession(sessionId, force)}
      runLink={(run) => `/runs?text=${encodeURIComponent(run)}`}
      onClose={onClose}
      onDone={() => {
        void queryClient.invalidateQueries({ queryKey: ['sessions'] });
        void navigate('/sessions');
        onClose();
      }}
    />
  );
}

/** Confirm and delete a workflow; afterwards its session page opens. */
export function DeleteWorkflowDialog({
  sessionId,
  workflowId,
  onClose,
}: {
  sessionId: string;
  workflowId: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return (
    <ConfirmDeleteDialog
      title={`Delete workflow ${workflowId}`}
      id={workflowId}
      removes="the workflow and its saved versions"
      keeps="its run history and logs, which stay readable"
      remove={(force) => deleteWorkflow(sessionId, workflowId, force)}
      runLink={(run) => `/runs?text=${encodeURIComponent(run)}`}
      onClose={onClose}
      onDone={() => {
        void queryClient.invalidateQueries({ queryKey: ['sessions'] });
        void navigate(`/sessions/${encodeURIComponent(sessionId)}`);
        onClose();
      }}
    />
  );
}
