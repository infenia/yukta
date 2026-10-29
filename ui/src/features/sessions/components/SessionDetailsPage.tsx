// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Modal } from '@/components/common/Modal';
import { useSessionDetails } from '../hooks/useSessionDetails';
import { useSessionWorkflows } from '../hooks/useSessionWorkflows';
import { SessionInfoPanel } from './SessionInfoPanel';
import { SessionRowActionsMenu } from './SessionRowActionsMenu';
import { WorkflowTable } from './WorkflowTable';
import { RecentRuns } from './RecentRuns';
import { StateMessage } from '@/components/common/StateMessage';

/** Asks for the new workflow's ID, then opens the editor on it. */
function NewWorkflowDialog({
  sessionId,
  onClose,
}: {
  sessionId: string;
  onClose: () => void;
}) {
  const navigate = useNavigate();
  return (
    <Modal title="New workflow" onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          const id = new FormData(event.currentTarget).get('workflowId');
          if (typeof id === 'string' && id.trim()) {
            navigate(
              `/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(id.trim())}/edit`
            );
          }
        }}
      >
        <label className="flex flex-col gap-1.5">
          <span className="text-label-md">Workflow ID</span>
          <Input
            name="workflowId"
            autoFocus
            required
            placeholder="e.g. build-and-test"
            className="font-mono"
          />
          <span className="text-body-sm text-muted-foreground">
            Used in URLs, the CLI and MCP calls.
          </span>
        </label>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit">Create and edit</Button>
        </div>
      </form>
    </Modal>
  );
}

export function SessionDetailsPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const {
    data: session,
    isLoading: sessionLoading,
    error: sessionError,
  } = useSessionDetails(sessionId || '');
  const {
    data: workflows = [],
    isLoading: workflowsLoading,
    error: workflowsError,
  } = useSessionWorkflows(sessionId || '');
  const [workflowFilter, setWorkflowFilter] = useState('');
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    setWorkflowFilter('');
  }, [sessionId]);

  if (sessionLoading || workflowsLoading) {
    return (
      <div className="px-4 py-6 sm:px-6">
        <StateMessage tone="loading" title="Loading session details..." />
      </div>
    );
  }

  if (sessionError || workflowsError) {
    return (
      <div className="px-4 py-6 sm:px-6">
        <StateMessage tone="error" title="Failed to load session details" />
      </div>
    );
  }

  if (!session) {
    return (
      <div className="px-4 py-6 sm:px-6">
        <StateMessage
          tone="neutral"
          title="Session not found"
          icon="search_off"
        />
      </div>
    );
  }

  return (
    <div className="bg-background flex flex-1 flex-col gap-0">
      <div>
        <SessionInfoPanel
          session={session}
          actions={
            <div className="flex items-center gap-2">
              <Button type="button" onClick={() => setCreating(true)}>
                <span className="material-symbols-outlined text-base">add</span>
                New workflow
              </Button>
              <SessionRowActionsMenu session={session} showView={false} />
            </div>
          }
        />
        <WorkflowTable
          sessionId={session.sessionId}
          workflows={workflows}
          filter={workflowFilter}
          onFilterChange={setWorkflowFilter}
        />
        <RecentRuns sessionId={session.sessionId} />
      </div>
      {creating && (
        <NewWorkflowDialog
          sessionId={session.sessionId}
          onClose={() => setCreating(false)}
        />
      )}
    </div>
  );
}
