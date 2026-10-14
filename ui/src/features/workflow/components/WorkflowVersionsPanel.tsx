// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { InlineError } from '@/components/common/InlineError';
import { controlErrorMessage } from '../hooks/useExecutionControl';
import {
  getDiff,
  getVersions,
  rollback,
  type WorkflowDiff,
} from '../api/versions';

interface Props {
  sessionId: string;
  workflowId: string;
}

const changes = (diff: WorkflowDiff): string[] => [
  ...(diff.descriptionChanged ? ['description changed'] : []),
  ...diff.addedNodes.map((id) => `+ node ${id}`),
  ...diff.removedNodes.map((id) => `- node ${id}`),
  ...diff.changedNodes.map((id) => `~ node ${id}`),
  ...diff.addedEdges.map((edge) => `+ edge ${edge}`),
  ...diff.removedEdges.map((edge) => `- edge ${edge}`),
];

/** The saved versions of a workflow: compare one with the current definition, or go back to it. */
export function WorkflowVersionsPanel({ sessionId, workflowId }: Props) {
  const queryClient = useQueryClient();
  const [compared, setCompared] = useState<string | null>(null);
  const versions = useQuery({
    queryKey: ['sessions', sessionId, 'workflows', workflowId, 'versions'],
    queryFn: () => getVersions(sessionId, workflowId),
  });
  const diff = useQuery({
    queryKey: [
      'sessions',
      sessionId,
      'workflows',
      workflowId,
      'diff',
      compared,
    ],
    queryFn: () => getDiff(sessionId, workflowId, compared as string),
    enabled: compared !== null,
  });
  const goBack = useMutation({
    mutationFn: (version: string) => rollback(sessionId, workflowId, version),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: ['sessions', sessionId, 'workflows'],
      }),
  });

  return (
    <section
      aria-label="Workflow versions"
      className="border-outline-variant bg-surface-container-low flex flex-col gap-2 border-b px-6 py-3"
    >
      {(versions.error || goBack.error) && (
        <InlineError
          message={controlErrorMessage(versions.error ?? goBack.error)}
        />
      )}
      {versions.data?.length === 0 && (
        <p className="text-on-surface-variant text-sm">
          No saved versions yet.
        </p>
      )}
      {versions.data?.map((version) => (
        <div key={version.id} className="flex flex-col gap-1">
          <div className="flex items-center gap-3">
            <span className="text-on-surface font-mono text-sm">
              {version.id}
            </span>
            <span className="text-on-surface-variant text-sm">
              {new Date(version.savedAt).toLocaleString()}
            </span>
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                setCompared(compared === version.id ? null : version.id)
              }
            >
              {compared === version.id ? 'Hide changes' : 'Changes since'}
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={goBack.isPending}
              onClick={() => goBack.mutate(version.id)}
            >
              Roll back
            </Button>
          </div>
          {compared === version.id && diff.data && (
            <ul className="text-on-surface-variant ml-4 font-mono text-xs">
              {changes(diff.data).length === 0 ? (
                <li>Same as the current definition</li>
              ) : (
                changes(diff.data).map((line) => <li key={line}>{line}</li>)
              )}
            </ul>
          )}
        </div>
      ))}
    </section>
  );
}
