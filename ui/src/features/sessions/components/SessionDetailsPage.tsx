// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Input } from '@/components/ui/input';
import { Modal } from '@/components/common/Modal';
import { useSessionDetails } from '../hooks/useSessionDetails';
import { useSessionWorkflows } from '../hooks/useSessionWorkflows';
import { SessionInfoPanel } from './SessionInfoPanel';
import { SessionActions } from './SessionActions';
import { WorkflowTable } from './WorkflowTable';
import { RecentRuns } from './RecentRuns';
import { ScopedSupervisors } from '@/features/control/supervisors/ScopedSupervisors';
import { StateMessage } from '@/components/common/StateMessage';
import { useQuery } from '@tanstack/react-query';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Button, buttonVariants } from '@/components/ui/button';
import { getSessionDraft } from '@/features/builder/api';
import {
  CopyAsMenu,
  type CopyChoice,
} from '@/features/workflow/components/CopyAsMenu';
import { useCopyCommand } from '@/features/workflow/hooks/useCopyCommand';

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

/** The session as an MCP call, curl or the CLI: create it, or run one of its workflows. */
function SessionCopyAs({
  sessionId,
  workflowIds,
}: {
  sessionId: string;
  workflowIds: string[];
}) {
  const draft = useQuery({
    queryKey: ['session-draft', sessionId],
    queryFn: () => getSessionDraft(sessionId),
  });
  const { status, copy } = useCopyCommand();
  const choices: CopyChoice[] = [
    ...(draft.data
      ? [
          {
            label: 'Create this session',
            action: { kind: 'createSession', session: draft.data } as const,
          },
        ]
      : []),
    ...workflowIds.map((workflowId) => ({
      label: `Run ${workflowId}`,
      action: { kind: 'start', sessionId, workflowId } as const,
    })),
  ];
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger
          className={buttonVariants({ variant: 'outline' })}
          aria-label="Copy as"
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base"
          >
            content_copy
          </span>
          Copy as…
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <CopyAsMenu choices={choices} onCopy={copy} />
        </DropdownMenuContent>
      </DropdownMenu>
      {status && (
        <span role="status" className="text-label-sm text-on-surface-variant">
          {status === 'copied' ? 'Copied' : 'Copy failed'}
        </span>
      )}
    </>
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
          workflows={{
            count: workflows.length,
            nodes: workflows.reduce((sum, w) => sum + w.nodeCount, 0),
          }}
          actions={
            <div className="flex items-center gap-2">
              <Button type="button" onClick={() => setCreating(true)}>
                <span className="material-symbols-outlined text-base">add</span>
                New workflow
              </Button>
              <SessionCopyAs
                sessionId={session.sessionId}
                workflowIds={workflows.map((w) => w.workflowId)}
              />
              <SessionActions sessionId={session.sessionId} />
            </div>
          }
        />
        <WorkflowTable
          sessionId={session.sessionId}
          workflows={workflows}
          filter={workflowFilter}
          onFilterChange={setWorkflowFilter}
        />
        <ScopedSupervisors sessionId={session.sessionId} />
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
