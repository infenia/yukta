// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link } from 'react-router';
import { Button, buttonVariants } from '@/components/ui/button';
import { RequirePermission } from '@/components/common/RequirePermission';
import { DeleteSessionDialog } from './DeleteDialogs';
import { EXPORT_HINT, exportSessionConfig } from '../lib/exportSession';

/** Edit, Export JSON and Delete of a session, as buttons on the session's own page. */
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
      <Link
        to={`/sessions/${encodeURIComponent(sessionId)}/edit`}
        className={buttonVariants({ variant: 'outline' })}
      >
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-base"
        >
          edit
        </span>
        Edit
      </Link>
      <Button
        type="button"
        variant="outline"
        title={EXPORT_HINT}
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
      >
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-base"
        >
          download
        </span>
        Export JSON
      </Button>
      <RequirePermission perm="deleteSessions">
        <Button
          type="button"
          variant="outline"
          onClick={() => setDeleting(true)}
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base"
          >
            delete
          </span>
          Delete
        </Button>
      </RequirePermission>
      {deleting && (
        <DeleteSessionDialog
          sessionId={sessionId}
          onClose={() => setDeleting(false)}
        />
      )}
    </>
  );
}
