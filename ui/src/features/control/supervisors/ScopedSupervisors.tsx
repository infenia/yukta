// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link } from 'react-router';
import { Button } from '@/components/ui/button';
import { scopeApplies, scopeLabel } from '../api/supervisors';
import { AttachDialog } from './AttachDialog';
import { useBindings } from './useSupervisorData';

/**
 * The supervisors that apply to a session, or to one of its workflows, with a button to attach
 * another here. Shows nothing while the list loads or if it cannot be read: it is an aside, and
 * the Control page reports problems.
 */
export function ScopedSupervisors({
  sessionId,
  workflowId,
  className = '',
}: {
  sessionId: string;
  workflowId?: string;
  /** Spacing from the page; it lives here so nothing is left behind while this shows nothing. */
  className?: string;
}) {
  const bindings = useBindings();
  const [attaching, setAttaching] = useState(false);
  if (bindings.isPending || bindings.error) return null;
  const here = bindings.data.filter((b) =>
    scopeApplies(b.scope, sessionId, workflowId)
  );
  return (
    <section
      aria-label="Supervisors"
      className={`flex flex-wrap items-center gap-2 rounded-xl border border-border bg-card px-4 py-2 ${className}`}
    >
      <h2 className="text-label-md text-on-surface">Supervisors</h2>
      {here.length === 0 ? (
        <span className="text-body-sm text-on-surface-variant">
          None apply to this {workflowId ? 'workflow' : 'session'}.
        </span>
      ) : (
        <ul className="flex flex-wrap gap-1">
          {here.map((b) => (
            <li
              key={b.id}
              className={`rounded-full bg-muted px-2 py-0.5 text-label-sm ${b.enabled ? '' : 'opacity-60'}`}
            >
              {b.profileId} · {scopeLabel(b.scope)}
              {!b.enabled && ' (disabled)'}
            </li>
          ))}
        </ul>
      )}
      <span className="ml-auto flex gap-2">
        <Link className="text-label-md underline" to="/control">
          Manage
        </Link>
        <Button size="sm" variant="outline" onClick={() => setAttaching(true)}>
          Attach supervisor
        </Button>
      </span>
      {attaching && (
        <AttachDialog
          preset={
            workflowId
              ? { kind: 'workflow', sessionId, workflowId }
              : { kind: 'session', sessionId }
          }
          onClose={() => setAttaching(false)}
        />
      )}
    </section>
  );
}
