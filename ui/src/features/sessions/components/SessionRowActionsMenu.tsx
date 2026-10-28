// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { DeleteSessionDialog } from './DeleteDialogs';
import { RequirePermission } from '@/components/common/RequirePermission';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { SessionListItem } from '../types/session';

interface SessionRowActionsMenuProps {
  session: SessionListItem;
}

export function SessionRowActionsMenu({ session }: SessionRowActionsMenuProps) {
  const navigate = useNavigate();
  const [deleting, setDeleting] = useState(false);

  const handleView = () => {
    navigate(`/sessions/${session.sessionId}`);
  };

  const handleEdit = () => {
    navigate(`/sessions/${encodeURIComponent(session.sessionId)}/edit`);
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={(state) => (
          <Button
            variant="ghost"
            size="sm"
            aria-label="Session actions"
            {...state}
            onClick={(e: React.MouseEvent) => {
              e.stopPropagation();
              state.onClick?.(e);
            }}
          >
            <span className="material-symbols-outlined" aria-hidden="true">
              more_vert
            </span>
          </Button>
        )}
      />
      <DropdownMenuContent align="end">
        <DropdownMenuItem
          onClick={(e) => {
            e.stopPropagation();
            handleView();
          }}
        >
          View Details
        </DropdownMenuItem>
        <DropdownMenuItem
          onClick={(e) => {
            e.stopPropagation();
            handleEdit();
          }}
        >
          Edit
        </DropdownMenuItem>
        <RequirePermission perm="deleteSessions">
          <DropdownMenuItem
            onClick={(e) => {
              e.stopPropagation();
              setDeleting(true);
            }}
          >
            Delete session…
          </DropdownMenuItem>
        </RequirePermission>
      </DropdownMenuContent>
      {deleting && (
        <DeleteSessionDialog
          sessionId={session.sessionId}
          onClose={() => setDeleting(false)}
        />
      )}
    </DropdownMenu>
  );
}
