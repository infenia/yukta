// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { NativeSelect } from '@/components/ui/native-select';
import { Callout } from '@/components/common/Callout';
import { Modal } from '@/components/common/Modal';
import { ApiError } from '@/lib/apiClient';
import { buildScope } from '@/features/control/api/supervisors';
import { getSessionWorkflows } from '@/features/sessions/api/getSessionWorkflows';
import { useSessionSummaries } from '@/features/sessions/hooks/useSessionSummaries';
import { getWorkflowNodeIds } from '../api/supervisors';
import { useAttachSupervisor } from '../hooks/useSupervisorTypes';

type Kind = 'global' | 'session' | 'workflow' | 'section';

const KINDS: { key: Kind; label: string }[] = [
  { key: 'global', label: 'Global' },
  { key: 'session', label: 'Session' },
  { key: 'workflow', label: 'Workflow' },
  { key: 'section', label: 'Nodes' },
];

/** The part of a node id before its first dot: the group the picker puts it under. */
const prefixOf = (nodeId: string) =>
  nodeId.includes('.') ? nodeId.slice(0, nodeId.indexOf('.')) : '';

/** Node ids grouped by prefix, in the order they first appear. */
export function groupNodes(ids: string[]): [string, string[]][] {
  const groups = new Map<string, string[]>();
  for (const id of ids) {
    groups.set(prefixOf(id), [...(groups.get(prefixOf(id)) ?? []), id]);
  }
  return [...groups.entries()];
}

/** Attaches a supervisor to a scope: everything, a session, a workflow of it, or some of its nodes. */
export function AttachDialog({
  supervisorId,
  onClose,
}: {
  supervisorId: string;
  onClose: () => void;
}) {
  const sessions = useSessionSummaries().data ?? [];
  const attach = useAttachSupervisor();
  const [kind, setKind] = useState<Kind>('session');
  const [sessionId, setSessionId] = useState('');
  const [workflowId, setWorkflowId] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const [enabled, setEnabled] = useState(true);
  const workflows = useQuery({
    queryKey: ['supervisors', 'dialog', 'workflows', sessionId],
    queryFn: () => getSessionWorkflows(sessionId),
    enabled: sessionId !== '' && (kind === 'workflow' || kind === 'section'),
  });
  const nodes = useQuery({
    queryKey: ['supervisors', 'dialog', 'nodes', sessionId, workflowId],
    queryFn: () => getWorkflowNodeIds(sessionId, workflowId),
    enabled: sessionId !== '' && workflowId !== '' && kind === 'section',
  });
  const scope = buildScope(kind, sessionId, workflowId, picked.join(','));
  const failure =
    attach.error instanceof ApiError && attach.error.status === 403
      ? 'Attaching supervisors needs an ADMIN key.'
      : attach.error?.message;
  const toggle = (id: string) =>
    setPicked((now) =>
      now.includes(id) ? now.filter((n) => n !== id) : [...now, id]
    );

  return (
    <Modal title={`Attach ${supervisorId}`} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <div
          role="radiogroup"
          aria-label="Where"
          className="flex flex-wrap gap-2"
        >
          {KINDS.map(({ key, label }) => (
            <label key={key} className="text-label-md flex items-center gap-1">
              <input
                type="radio"
                name="scope-kind"
                checked={kind === key}
                onChange={() => {
                  setKind(key);
                  setPicked([]);
                }}
              />
              {label}
            </label>
          ))}
        </div>
        {kind !== 'global' && (
          <label className="text-label-md flex flex-col gap-1">
            Session
            <NativeSelect
              aria-label="Choose session"
              value={sessionId}
              onChange={(event) => {
                setSessionId(event.target.value);
                setWorkflowId('');
                setPicked([]);
              }}
            >
              <option value="">Choose a session</option>
              {sessions.map((s) => (
                <option key={s.sessionId} value={s.sessionId}>
                  {s.name || s.sessionId}
                </option>
              ))}
            </NativeSelect>
          </label>
        )}
        {(kind === 'workflow' || kind === 'section') && sessionId !== '' && (
          <label className="text-label-md flex flex-col gap-1">
            Workflow
            <NativeSelect
              aria-label="Choose workflow"
              value={workflowId}
              onChange={(event) => {
                setWorkflowId(event.target.value);
                setPicked([]);
              }}
            >
              <option value="">Choose a workflow</option>
              {(workflows.data ?? []).map((w) => (
                <option key={w.workflowId} value={w.workflowId}>
                  {w.workflowId}
                </option>
              ))}
            </NativeSelect>
          </label>
        )}
        {kind === 'section' && workflowId !== '' && (
          <fieldset className="border-border max-h-48 overflow-auto rounded-lg border p-3">
            <legend className="text-label-md px-1">Nodes</legend>
            {groupNodes(nodes.data ?? []).map(([prefix, ids]) => (
              <div key={prefix} className="mb-2">
                <div className="text-label-sm text-on-surface-variant">
                  {prefix ? `${prefix}.*` : 'No prefix'}
                </div>
                {ids.map((id) => (
                  <label
                    key={id}
                    className="text-body-md flex items-center gap-2"
                  >
                    <input
                      type="checkbox"
                      checked={picked.includes(id)}
                      onChange={() => toggle(id)}
                    />
                    {id}
                  </label>
                ))}
              </div>
            ))}
          </fieldset>
        )}
        <div>
          <div className="text-label-sm text-on-surface-variant">Scope</div>
          <code className="text-code-sm" aria-label="Scope preview">
            {scope ?? '—'}
          </code>
        </div>
        <label className="text-label-md flex items-center gap-2">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(event) => setEnabled(event.target.checked)}
          />
          Start now
        </label>
        {failure && <Callout kind="danger">{failure}</Callout>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="button"
            disabled={scope === null || attach.isPending}
            onClick={() =>
              attach.mutate(
                { supervisorId, scope: scope as string, enabled },
                { onSuccess: onClose }
              )
            }
          >
            Attach
          </Button>
        </div>
      </div>
    </Modal>
  );
}
