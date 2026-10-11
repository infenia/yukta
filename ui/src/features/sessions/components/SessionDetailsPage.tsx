// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useSessionDetails } from '../hooks/useSessionDetails';
import { useSessionWorkflows } from '../hooks/useSessionWorkflows';
import { SessionInfoPanel } from './SessionInfoPanel';
import { WorkflowGrid } from './WorkflowGrid';

export function SessionDetailsPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const { data: session, isLoading: sessionLoading, error: sessionError } = useSessionDetails(sessionId || '');
  const { data: workflows = [], isLoading: workflowsLoading, error: workflowsError } = useSessionWorkflows(sessionId || '');
  const [workflowFilter, setWorkflowFilter] = useState('');
  const navigate = useNavigate();

  useEffect(() => {
    setWorkflowFilter('');
  }, [sessionId]);

  if (sessionLoading || workflowsLoading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-on-surface-variant">Loading session details...</p>
      </div>
    );
  }

  if (sessionError || workflowsError) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-error">Failed to load session details</p>
      </div>
    );
  }

  if (!session) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-error">Session not found</p>
      </div>
    );
  }

  return (
    <div className="flex flex-1 flex-col gap-0 bg-background">
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
