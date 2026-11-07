// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link } from 'react-router';
import { Tip } from '@/components/ui/tooltip';
import { iconButton } from '@/components/ui/iconTone';
import { DropdownMenuItem } from '@/components/ui/dropdown-menu';
import { IconButton } from '@/components/common/IconButton';
import { OverflowMenu } from '@/components/common/OverflowMenu';
import { RequirePermission } from '@/components/common/RequirePermission';
import { DeleteSessionDialog } from './DeleteDialogs';
import { EXPORT_HINT, exportSessionConfig } from '../lib/exportSession';

/** Edit and Export JSON as icon buttons; Delete sits in the ⋯ menu. */
export function SessionActions({ sessionId }: { sessionId: string }) {
  const [deleting, setDeleting] = useState(false);
  // why the last export failed, or null
  const [exportError, setExportError] = useState<string | null>(null);

  return (
    <>
      {exportError !== null && (
        <span role="alert" className="text-body-sm text-error">
          {exportError}
        </span>
      )}
      <Tip label="Edit session">
        <Link
          to={`/sessions/${encodeURIComponent(sessionId)}/edit`}
          aria-label="Edit"
          className={iconButton('primary')}
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base"
          >
            edit
          </span>
        </Link>
      </Tip>
      <IconButton
        icon="download"
        label="Export JSON"
        tip={EXPORT_HINT}
        tone="primary"
        onClick={() => {
          setExportError(null);
          exportSessionConfig(sessionId).catch((error: unknown) =>
            setExportError(
              error instanceof Error
                ? `Export failed: ${error.message}`
                : 'Export failed'
            )
          );
        }}
      />
      <OverflowMenu label="Session actions">
        <RequirePermission perm="deleteSessions">
          <DropdownMenuItem
            variant="destructive"
            onClick={() => setDeleting(true)}
          >
            Delete
          </DropdownMenuItem>
        </RequirePermission>
      </OverflowMenu>
      {deleting && (
        <DeleteSessionDialog
          sessionId={sessionId}
          onClose={() => setDeleting(false)}
        />
      )}
    </>
  );
}
