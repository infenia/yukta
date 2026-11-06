// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { StateMessage } from '@/components/common/StateMessage';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { scopeLabel } from '../lib/scope';
import {
  useDetachAttachment,
  useSetAttachmentEnabled,
} from '../hooks/useSupervisorTypes';
import type { AttachmentView } from '../types';

const SOURCE_LABEL = {
  MANUAL: 'Manual',
  DEFINITION: 'Definition',
  SYSTEM: 'System',
} as const;

/** The page that declares a DEFINITION attachment. */
function origin(a: AttachmentView) {
  if (!a.originSessionId) return null;
  const to = a.originWorkflowId
    ? `/sessions/${encodeURIComponent(a.originSessionId)}/workflow/${encodeURIComponent(a.originWorkflowId)}`
    : `/sessions/${encodeURIComponent(a.originSessionId)}`;
  return (
    <Link to={to} className="text-primary hover:underline">
      {a.originWorkflowId
        ? `${a.originSessionId} › ${a.originWorkflowId}`
        : a.originSessionId}
    </Link>
  );
}

/** Attachments with their source; only manual ones can be paused or removed here. */
export function AttachmentsTable({
  attachments,
  showSupervisor = false,
}: {
  attachments: AttachmentView[];
  showSupervisor?: boolean;
}) {
  const toggle = useSetAttachmentEnabled();
  const detach = useDetachAttachment();
  if (attachments.length === 0) {
    return <StateMessage compact title="No attachments." />;
  }
  return (
    <TableFrame>
      <Table>
        <TableHeader>
          <TableRow>
            {showSupervisor && <TableHead>Supervisor</TableHead>}
            <TableHead>Scope</TableHead>
            <TableHead>Source</TableHead>
            <TableHead>Origin</TableHead>
            <TableHead>
              <span className="sr-only">Actions</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {attachments.map((a) => (
            <TableRow key={a.id}>
              {showSupervisor && (
                <TableCell>
                  <Link
                    to={`/supervisors/${encodeURIComponent(a.supervisorId)}`}
                    className="text-primary hover:underline"
                  >
                    {a.supervisorId}
                  </Link>
                </TableCell>
              )}
              <TableCell>
                {scopeLabel(a.scope)}
                {!a.enabled && (
                  <span className="text-on-surface-variant"> (disabled)</span>
                )}
              </TableCell>
              <TableCell>{SOURCE_LABEL[a.source]}</TableCell>
              <TableCell>{origin(a) ?? '—'}</TableCell>
              <TableCell className="text-right">
                {a.source === 'MANUAL' ? (
                  <span className="inline-flex gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() =>
                        toggle.mutate({ id: a.id, enabled: !a.enabled })
                      }
                    >
                      {a.enabled ? 'Disable' : 'Enable'}
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => detach.mutate(a.id)}
                    >
                      Detach
                    </Button>
                  </span>
                ) : (
                  <span className="text-label-sm text-on-surface-variant">
                    {a.source === 'DEFINITION'
                      ? 'Edit the definition'
                      : 'Set by the server'}
                  </span>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableFrame>
  );
}
