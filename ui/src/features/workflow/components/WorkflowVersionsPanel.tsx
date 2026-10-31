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
  MAX_NOTE,
  type WorkflowDiff,
} from '../api/versions';
import { StateMessage } from '@/components/common/StateMessage';
import { Modal } from '@/components/common/Modal';

interface Props {
  sessionId: string;
  workflowId: string;
  /** A run of this workflow is going: going back would change it under the run. */
  liveRun?: boolean;
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
export function WorkflowVersionsPanel({
  sessionId,
  workflowId,
  liveRun = false,
}: Props) {
  const queryClient = useQueryClient();
  const [compared, setCompared] = useState<string | null>(null);
  // The version whose rollback awaits confirmation, shown with what it would change
  const [confirming, setConfirming] = useState<string | null>(null);
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
  const confirmDiff = useQuery({
    queryKey: [
      'sessions',
      sessionId,
      'workflows',
      workflowId,
      'diff',
      confirming,
    ],
    queryFn: () => getDiff(sessionId, workflowId, confirming as string),
    enabled: confirming !== null,
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
            <span className="text-code-sm text-on-surface">{version.id}</span>
            <span className="text-body-md text-on-surface-variant">
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
                disabled={
                  liveRun ||
                  (goBack.isPending && goBack.variables === version.id)
                }
                title={
                  liveRun
                    ? 'A run of this workflow is still going. Stop it first'
                    : undefined
                }
                onClick={() => setConfirming(version.id)}
              >
                Roll back
              </Button>
            </RequirePermission>
          </div>
          <div className="flex items-center gap-2">
            <input
              aria-label={`Note for ${version.id}`}
              maxLength={MAX_NOTE}
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
              className="text-body-sm text-on-surface-variant ml-4"
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
              className="text-code-sm text-on-surface-variant ml-4"
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
      {confirming && (
        <Modal
          title={`Roll back to ${confirming}?`}
          onClose={() => setConfirming(null)}
        >
          <p className="text-body-md text-on-surface-variant mb-2">
            This replaces the current definition. Changes since that version:
          </p>
          {confirmDiff.isLoading && (
            <p role="status" className="text-body-sm">
              Loading changes…
            </p>
          )}
          {confirmDiff.error && (
            <InlineError message={controlErrorMessage(confirmDiff.error)} />
          )}
          {confirmDiff.data && (
            <ul className="text-code-sm text-on-surface-variant mb-4">
              {changes(confirmDiff.data).length === 0 ? (
                <li>Same as the current definition</li>
              ) : (
                changes(confirmDiff.data).map((line) => (
                  <li key={line}>{line}</li>
                ))
              )}
            </ul>
          )}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setConfirming(null)}>
              Cancel
            </Button>
            <Button
              onClick={() => {
                goBack.mutate(confirming);
                setConfirming(null);
              }}
            >
              Roll back
            </Button>
          </div>
        </Modal>
      )}
    </section>
  );
}
