// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link } from 'react-router';
import { Button, buttonVariants } from '@/components/ui/button';
import { scopeLabel } from '@/features/control/api/supervisors';
import { useAttachments } from '../hooks/useSupervisorTypes';
import type { AttachmentView } from '../types';
import { AttachDialog } from './AttachDialog';

/** Where an attachment comes from, as seen from this session or workflow. */
function badge(a: AttachmentView, sessionId: string, workflowId?: string) {
  if (a.source === 'SYSTEM') return 'System';
  if (a.source === 'MANUAL') return 'Manual';
  const here =
    a.originSessionId === sessionId &&
    (workflowId === undefined
      ? a.originWorkflowId === null
      : a.originWorkflowId === workflowId);
  return here ? 'Definition' : 'Inherited';
}

/**
 * The supervisors that apply to a session, or to one of its workflows: those its definition
 * declares, those attached by hand, those it inherits from a wider scope and the server's own.
 * Shows nothing while the list loads or if it cannot be read: it is an aside.
 */
export function EffectiveSupervision({
  sessionId,
  workflowId,
  className = '',
}: {
  sessionId: string;
  workflowId?: string;
  className?: string;
}) {
  const { data, isPending, error } = useAttachments({
    session: sessionId,
    workflow: workflowId,
  });
  const [attaching, setAttaching] = useState(false);
  if (isPending || error) return null;
  const configure = workflowId
    ? `/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflowId)}/edit`
    : `/sessions/${encodeURIComponent(sessionId)}/edit`;
  return (
    <section
      aria-label="Supervisors"
      className={`border-border bg-card flex flex-col gap-2 rounded-xl border px-4 py-3 ${className}`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-label-md text-on-surface">
          Supervision that applies here
        </h2>
        <span className="ml-auto flex gap-2">
          <Link
            to={configure}
            className={buttonVariants({ variant: 'outline', size: 'sm' })}
          >
            Configure
          </Link>
          <Button
            size="sm"
            variant="outline"
            onClick={() => setAttaching(true)}
          >
            Attach manually
          </Button>
        </span>
      </div>
      {data.length === 0 ? (
        <span className="text-body-sm text-on-surface-variant">
          None apply to this {workflowId ? 'workflow' : 'session'}.
        </span>
      ) : (
        <ul className="flex flex-col gap-1">
          {data.map((a) => (
            <li
              key={a.id}
              className={`flex flex-wrap items-center gap-2 ${a.enabled ? '' : 'opacity-60'}`}
            >
              <Link
                to={`/supervisors/${encodeURIComponent(a.supervisorId)}`}
                className="text-body-md text-primary font-medium hover:underline"
              >
                {a.supervisorId}
              </Link>
              <code className="text-code-sm text-on-surface-variant">
                {scopeLabel(a.scope)}
              </code>
              <span className="bg-muted text-label-sm rounded-full px-2 py-0.5">
                {badge(a, sessionId, workflowId)}
              </span>
              {!a.enabled && (
                <span className="text-label-sm text-on-surface-variant">
                  disabled
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {attaching && (
        <AttachDialog
          preset={{ sessionId, workflowId }}
          onClose={() => setAttaching(false)}
        />
      )}
    </section>
  );
}
