// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { NativeSelect } from '@/components/ui/native-select';
import { Callout } from '@/components/common/Callout';
import { Modal } from '@/components/common/Modal';
import { ApiError } from '@/lib/apiClient';
import { useSessionSummaries } from '@/features/sessions/hooks/useSessionSummaries';
import { buildScope } from '../api/supervisors';
import { useAttach, useProfiles } from './useSupervisorData';

type Kind = 'global' | 'session' | 'workflow' | 'section';

/** Where the supervisor applies when the dialog opens; the page that opens it knows. */
export interface ScopePreset {
  kind: Kind;
  sessionId?: string;
  workflowId?: string;
  /** Node selectors for a section, separated by commas. */
  nodes?: string;
}

/** Attaches a profile to a scope: everything, one session, one workflow or a section of one. */
export function AttachDialog({
  preset,
  onClose,
}: {
  preset?: ScopePreset;
  onClose: () => void;
}) {
  const profiles = useProfiles().data ?? [];
  const sessions = useSessionSummaries().data ?? [];
  const attach = useAttach();
  const [profileId, setProfileId] = useState('');
  const [kind, setKind] = useState<Kind>(preset?.kind ?? 'global');
  const [sessionId, setSessionId] = useState(preset?.sessionId ?? '');
  const [workflowId, setWorkflowId] = useState(preset?.workflowId ?? '');
  const [nodes, setNodes] = useState(preset?.nodes ?? '');
  const [enabled, setEnabled] = useState(true);
  const chosenProfile = profileId || profiles[0]?.id || '';
  const scope = buildScope(kind, sessionId, workflowId, nodes);
  const problem =
    chosenProfile === ''
      ? 'Create a profile first.'
      : scopeProblem(scope, kind, workflowId);

  const submit = () => {
    // the Attach button is disabled while there is a problem, so the scope is set here
    attach.mutate(
      { profileId: chosenProfile, scope: scope as string, enabled },
      { onSuccess: onClose }
    );
  };
  const failure =
    attach.error instanceof ApiError && attach.error.status === 403
      ? 'Attaching supervisors needs an ADMIN key.'
      : attach.error?.message;

  return (
    <Modal title="Attach supervisor" onClose={onClose}>
      <div className="flex flex-col gap-3">
        <label className="text-label-md text-on-surface flex flex-col gap-1">
          Profile
          <NativeSelect
            aria-label="Profile"
            value={chosenProfile}
            onChange={(e) => setProfileId(e.target.value)}
          >
            {profiles.map((p) => (
              <option key={p.id} value={p.id}>
                {p.id} ({p.type})
              </option>
            ))}
          </NativeSelect>
        </label>
        <label className="text-label-md text-on-surface flex flex-col gap-1">
          Applies to
          <NativeSelect
            aria-label="Applies to"
            value={kind}
            onChange={(e) => setKind(e.target.value as Kind)}
          >
            <option value="global">Every run</option>
            <option value="session">One session</option>
            <option value="workflow">One workflow</option>
            <option value="section">A section of a workflow</option>
          </NativeSelect>
        </label>
        {kind !== 'global' && (
          <label className="text-label-md text-on-surface flex flex-col gap-1">
            {kind === 'session' ? 'Session' : 'Session (optional)'}
            <NativeSelect
              aria-label="Session"
              value={sessionId}
              onChange={(e) => setSessionId(e.target.value)}
            >
              <option value="">
                {kind === 'session' ? 'Choose a session' : 'Any session'}
              </option>
              {sessions.map((s) => (
                <option key={s.sessionId} value={s.sessionId}>
                  {s.name || s.sessionId}
                </option>
              ))}
            </NativeSelect>
          </label>
        )}
        {(kind === 'workflow' || kind === 'section') && (
          <label className="text-label-md text-on-surface flex flex-col gap-1">
            Workflow id
            <Input
              value={workflowId}
              onChange={(e) => setWorkflowId(e.target.value)}
            />
          </label>
        )}
        {kind === 'section' && (
          <label className="text-label-md text-on-surface flex flex-col gap-1">
            Nodes
            <Input
              aria-label="Nodes"
              value={nodes}
              placeholder="build.*, publish"
              onChange={(e) => setNodes(e.target.value)}
            />
            <span className="text-body-sm text-on-surface-variant">
              Node ids, or a prefix such as build.* for every node whose id
              starts with build. It sees and acts on these nodes only, never on
              the whole run.
            </span>
          </label>
        )}
        <label className="text-body-md flex items-center gap-2">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
          />
          Start it now
        </label>
        <p className="text-body-sm text-on-surface-variant">
          It supervises the runs in its scope from now on, including runs that
          are already going.
        </p>
        {problem && (
          <Callout kind="warning" compact>
            {problem}
          </Callout>
        )}
        {failure && (
          <Callout kind="danger" compact>
            {failure}
          </Callout>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            onClick={submit}
            disabled={problem !== null || attach.isPending}
          >
            Attach
          </Button>
        </div>
      </div>
    </Modal>
  );
}

/** What is missing for the scope, or null when it is complete. */
function scopeProblem(
  scope: string | null,
  kind: Kind,
  workflowId: string
): string | null {
  if (scope !== null) return null;
  if (kind === 'session') return 'Choose a session.';
  return workflowId ? 'Give at least one node.' : 'Give the workflow id.';
}
