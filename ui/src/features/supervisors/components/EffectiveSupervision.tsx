// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { scopeLabel } from '../lib/scope';
import { useAttachments } from '../hooks/useSupervisorTypes';
import type { AttachmentView } from '../types';
import { Tip } from '@/components/ui/tooltip';

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
 * The supervisors that apply to a session, or to one of its workflows, as one slim read-only row:
 * those its definition declares, those attached by hand, those it inherits from a wider scope and
 * the server's own. Shows nothing while the list loads, if it cannot be read or if none apply:
 * it is an aside.
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
  if (isPending || error || data.length === 0) return null;
  return (
    <section
      aria-label="Supervisors"
      className={`flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-2 sm:px-6 ${className}`}
    >
      <h2 className="text-overline">Supervision</h2>
      <ul className="flex flex-wrap items-center gap-x-4 gap-y-1">
        {data.map((a) => (
          <Tip label={scopeLabel(a.scope)}>
            <li
              key={a.id}
              className={`flex items-center gap-1.5 ${a.enabled ? '' : 'opacity-60'}`}
            >
              <Link
                to={`/supervisors/${encodeURIComponent(a.supervisorId)}`}
                className="text-body-md text-primary font-medium hover:underline"
              >
                {a.supervisorId}
              </Link>
              <span className="bg-muted text-label-sm rounded-full px-2 py-0.5">
                {badge(a, sessionId, workflowId)}
              </span>
              {!a.enabled && (
                <span className="text-label-sm text-on-surface-variant">
                  disabled
                </span>
              )}
            </li>
          </Tip>
        ))}
      </ul>
    </section>
  );
}
