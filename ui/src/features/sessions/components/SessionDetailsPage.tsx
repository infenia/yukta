// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState, useEffect } from 'react';
import { InlineError } from '@/components/common/InlineError';
import { useNavigate, useParams } from 'react-router';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useSessionDetails } from '../hooks/useSessionDetails';
import { useSessionWorkflows } from '../hooks/useSessionWorkflows';
import { SessionInfoPanel } from './SessionInfoPanel';
import { WorkflowGrid } from './WorkflowGrid';
import { StateMessage } from '@/components/common/StateMessage';
import { EXPORT_HINT, exportSessionConfig } from '../lib/exportSession';

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
  const [exportError, setExportError] = useState<string | null>(null);
  const navigate = useNavigate();

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
            <form
              className="flex items-center gap-2"
              onSubmit={(event) => {
                event.preventDefault();
                const id = new FormData(event.currentTarget).get('workflowId');
                if (typeof id === 'string' && id.trim()) {
                  navigate(
                    `/sessions/${encodeURIComponent(session.sessionId)}/workflow/${encodeURIComponent(id.trim())}/edit`
                  );
                }
              }}
            >
              <Input
                name="workflowId"
                aria-label="New workflow id"
                placeholder="new-workflow-id"
                className="w-48"
              />
              <Button type="submit" variant="outline">
                <span className="material-symbols-outlined text-base">add</span>
                New workflow
              </Button>
              <Button
                type="button"
                variant="outline"
                title={EXPORT_HINT}
                onClick={() => {
                  setExportError(null);
                  exportSessionConfig(session.sessionId).catch(
                    (error: unknown) =>
                      setExportError(
                        error instanceof Error ? error.message : 'Export failed'
                      )
                  );
                }}
              >
                <span className="material-symbols-outlined text-base">
                  download
                </span>
                Export JSON
              </Button>
              <InlineError message={exportError} />
            </form>
          }
        />
        <WorkflowGrid
          sessionId={session.sessionId}
          workflows={workflows}
          filter={workflowFilter}
          onFilterChange={setWorkflowFilter}
        />
      </div>
    </div>
  );
}
