// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { RequirePermission } from '@/components/common/RequirePermission';
import { DeleteSessionDialog } from './DeleteDialogs';

/**
 * The one action a session row offers besides opening it: Delete, as an icon button, for those who
 * may delete. Everything else about a session is on its page.
 */
export function SessionRowDelete({ sessionId }: { sessionId: string }) {
  const [deleting, setDeleting] = useState(false);
  return (
    <>
      <RequirePermission perm="deleteSessions">
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={`Delete session ${sessionId}`}
          title="Delete session"
          onClick={(event) => {
            event.stopPropagation();
            setDeleting(true);
          }}
        >
          <span className="material-symbols-outlined" aria-hidden="true">
            delete
          </span>
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
