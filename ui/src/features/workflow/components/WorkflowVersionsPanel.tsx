// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { RequirePermission } from '@/components/common/RequirePermission';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { inputFieldClassName } from '@/components/ui/native-select';
import { InlineError } from '@/components/common/InlineError';
import { controlErrorMessage } from '../hooks/useExecutionControl';
import {
  getDiff,
  getVersions,
  rollback,
  saveNote,
  type WorkflowDiff,
} from '../api/versions';
import { StateMessage } from '@/components/common/StateMessage';

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
  const [notes, setNotes] = useState<Record<string, string>>({});
  const writeNote = useMutation({
    mutationFn: ({ version, note }: { version: string; note: string }) =>
      saveNote(sessionId, workflowId, version, note),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: ['sessions', sessionId, 'workflows', workflowId, 'versions'],
      }),
  });
  const goBack = useMutation({
    mutationFn: (version: string) => rollback(sessionId, workflowId, version),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: ['sessions', sessionId],
      }),
  });

  return (
    <section
      aria-label="Workflow versions"
      className="border-outline-variant bg-surface-container-low flex flex-col gap-2 border-b px-6 py-3"
    >
      {(versions.error || goBack.error || writeNote.error) && (
        <InlineError
          message={controlErrorMessage(
            versions.error ?? goBack.error ?? writeNote.error
          )}
        />
      )}
      {versions.data?.length === 0 && (
        <StateMessage title="No saved versions yet." icon="history" compact />
      )}
      {versions.data?.map((version) => (
        <div key={version.id} className="flex flex-col gap-1">
          <div className="flex items-center gap-3">
            <span className="text-on-surface text-code-sm">{version.id}</span>
            <span className="text-on-surface-variant text-body-md">
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
            <RequirePermission perm="editSessions">
              <Button
                size="sm"
                variant="outline"
                disabled={goBack.isPending}
                onClick={() => {
                  if (
                    window.confirm(
                      `Roll back to ${version.id}? This replaces the current definition.`
                    )
                  ) {
                    goBack.mutate(version.id);
                  }
                }}
              >
                Roll back
              </Button>
            </RequirePermission>
          </div>
          <div className="flex items-center gap-2">
            <input
              aria-label={`Note for ${version.id}`}
              className={cn(inputFieldClassName, 'w-96 max-w-full')}
              placeholder="What is this version for?"
              value={notes[version.id] ?? version.note ?? ''}
              onChange={(event) =>
                setNotes({ ...notes, [version.id]: event.target.value })
              }
            />
            <RequirePermission perm="editSessions">
              <Button
                size="sm"
                variant="outline"
                disabled={writeNote.isPending}
                onClick={() =>
                  writeNote.mutate({
                    version: version.id,
                    note: notes[version.id] ?? version.note ?? '',
                  })
                }
              >
                Save note
              </Button>
            </RequirePermission>
          </div>
          {compared === version.id && diff.isLoading && (
            <p
              role="status"
              className="text-on-surface-variant text-body-sm ml-4"
            >
              Loading changes…
            </p>
          )}
          {compared === version.id && diff.error && (
            <InlineError message={controlErrorMessage(diff.error)} />
          )}
          {compared === version.id && diff.data && (
            <ul
              aria-live="polite"
              className="text-on-surface-variant text-code-sm ml-4"
            >
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
