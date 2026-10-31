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

type Kind = 'global' | 'session' | 'workflow';

/** Where the supervisor applies when the dialog opens; the page that opens it knows. */
export interface ScopePreset {
  kind: Kind;
  sessionId?: string;
  workflowId?: string;
}

/** Attaches a profile to a scope: everything, one session, or one workflow. */
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
  const [enabled, setEnabled] = useState(true);
  const chosenProfile = profileId || profiles[0]?.id || '';
  const scope = buildScope(kind, sessionId, workflowId);
  const problem =
    chosenProfile === ''
      ? 'Create a profile first.'
      : scope === null
        ? kind === 'session'
          ? 'Choose a session.'
          : 'Give the workflow id.'
        : null;

  const submit = () => {
    if (problem || scope === null) return;
    attach.mutate(
      { profileId: chosenProfile, scope, enabled },
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
        <label className="flex flex-col gap-1 text-label-md text-on-surface">
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
        <label className="flex flex-col gap-1 text-label-md text-on-surface">
          Applies to
          <NativeSelect
            aria-label="Applies to"
            value={kind}
            onChange={(e) => setKind(e.target.value as Kind)}
          >
            <option value="global">Every run</option>
            <option value="session">One session</option>
            <option value="workflow">One workflow</option>
          </NativeSelect>
        </label>
        {kind !== 'global' && (
          <label className="flex flex-col gap-1 text-label-md text-on-surface">
            {kind === 'workflow' ? 'Session (optional)' : 'Session'}
            <NativeSelect
              aria-label="Session"
              value={sessionId}
              onChange={(e) => setSessionId(e.target.value)}
            >
              <option value="">
                {kind === 'workflow' ? 'Any session' : 'Choose a session'}
              </option>
              {sessions.map((s) => (
                <option key={s.sessionId} value={s.sessionId}>
                  {s.name || s.sessionId}
                </option>
              ))}
            </NativeSelect>
          </label>
        )}
        {kind === 'workflow' && (
          <label className="flex flex-col gap-1 text-label-md text-on-surface">
            Workflow id
            <Input
              value={workflowId}
              onChange={(e) => setWorkflowId(e.target.value)}
            />
          </label>
        )}
        <label className="flex items-center gap-2 text-body-md">
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
