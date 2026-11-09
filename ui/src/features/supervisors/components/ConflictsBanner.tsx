// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Callout } from '@/components/common/Callout';
import { Button } from '@/components/ui/button';
import { RequirePermission } from '@/components/common/RequirePermission';
import {
  useAttachments,
  useSetAttachmentEnabled,
} from '../hooks/useSupervisorTypes';
import { parseScope, targetLabel } from '../lib/ownership';

/**
 * Start-up disables an assignment that overlaps one already kept (one owner per node). This says
 * so where people look, and lets them keep one instead. Shown only when there is a conflict; with
 * `workflow` only the conflicts of that workflow.
 */
export function ConflictsBanner({
  session,
  workflow,
}: {
  session?: string;
  workflow?: string;
}) {
  const { data = [] } = useAttachments();
  const keep = useSetAttachmentEnabled();
  const [open, setOpen] = useState(false);
  const conflicts = data.filter((a) => {
    if (!a.conflict) return false;
    if (!workflow) return true;
    const t = parseScope(a.scope);
    return (
      t.workflowId === workflow && (!t.sessionId || t.sessionId === session)
    );
  });
  if (conflicts.length === 0) return null;
  const count = conflicts.length;
  return (
    <Callout
      kind="warning"
      title={`${count} ${count === 1 ? 'assignment overlaps' : 'assignments overlap'} and ${count === 1 ? 'was' : 'were'} disabled.`}
    >
      <p>
        Each node has one owner; at start-up the first assignment was kept.{' '}
        <button
          type="button"
          className="text-primary"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          {open ? 'Hide' : 'Review'}
        </button>
      </p>
      {open && (
        <ul className="mt-2 flex flex-col gap-2">
          {conflicts.map((a) => (
            <li key={a.id} className="flex flex-wrap items-center gap-2">
              <b>{a.supervisorId}</b>
              <span>{targetLabel(parseScope(a.scope))}</span>
              <span className="flex-1" />
              <RequirePermission perm="editSessions">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={keep.isPending}
                  onClick={() => keep.mutate({ id: a.id, enabled: true })}
                >
                  Keep this one
                </Button>
              </RequirePermission>
            </li>
          ))}
        </ul>
      )}
      {keep.error && <p className="text-error mt-2">{keep.error.message}</p>}
    </Callout>
  );
}
